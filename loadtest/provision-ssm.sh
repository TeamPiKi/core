#!/usr/bin/env bash
# 부하테스트 환경(#911)의 앱 시크릿을 SSM 에 준비한다.
#
# 앱은 /piki-core/$ENVIRONMENT/* 를 읽는다(deploy.yml 이 박스에서 pull). loadtest 환경은
# 그 경로가 비어 있으므로 dev 값을 그대로 복제하고 db-host 만 부하 DB 박스로 바꾼다.
# dev 값을 쓰는 이유: OAuth·S3·Firebase 는 부하테스트에서 실제로 타지 않거나(토큰 발급은
# /api/v1/dev/** 로 우회) 타더라도 dev 리소스를 쓰는 게 맞다. prod 값을 복제하면 부하가
# prod 외부 자원을 건드린다.
#
# 값은 화면·파일에 남기지 않는다 — 이름과 성공 여부만 출력한다.
#
# 사용:
#   aws-vault exec piki --no-session -- ./loadtest/provision-ssm.sh <부하DB_사설IP>
#
# 원복(윈도우 종료 후) — 이 경로는 loadtest 전용이라 통째로 지우면 된다:
#   aws ssm get-parameters-by-path --path /piki-core/loadtest/ --recursive \
#     --query 'Parameters[].Name' --output text | xargs -n1 aws ssm delete-parameter --name
set -euo pipefail

SRC_ENV="${SRC_ENV:-dev}"
DST_ENV="${DST_ENV:-loadtest}"
REGION="${AWS_REGION:-ap-northeast-2}"

DB_HOST="${1:-}"
if [ -z "$DB_HOST" ]; then
  echo "사용법: $0 <부하DB_사설IP>" >&2
  echo "  예: $0 10.0.1.42   (terraform output loadtest_db_private_ip)" >&2
  exit 1
fi

echo "[ssm] /piki-core/$SRC_ENV/* -> /piki-core/$DST_ENV/* 복제 시작 (region=$REGION)"

# 이름·타입만 먼저 받는다(값 없이). 값은 아래에서 파라미터별로 받아 즉시 넘긴다 —
# 전량을 한 변수에 모아두면 프로세스 메모리에 오래 남고 실수로 출력될 여지가 커진다.
names=$(aws ssm get-parameters-by-path \
  --path "/piki-core/$SRC_ENV/" --recursive \
  --region "$REGION" \
  --query 'Parameters[].Name' --output text)

if [ -z "$names" ]; then
  echo "[ssm] 원본 파라미터가 없다 — /piki-core/$SRC_ENV/ 경로·자격증명 확인" >&2
  exit 1
fi

copied=0
skipped=0
for name in $names; do
  key="${name##*/}"

  # db-host 는 복제하지 않는다 — 부하 DB 박스를 가리켜야 한다(아래에서 따로 put).
  if [ "$key" = "db-host" ]; then
    skipped=$((skipped + 1))
    continue
  fi

  ptype=$(aws ssm get-parameter --name "$name" --region "$REGION" \
    --query 'Parameter.Type' --output text)

  # --with-decryption 으로 평문을 받아 같은 타입으로 넣는다. 값은 변수에만 담고 출력하지 않는다.
  value=$(aws ssm get-parameter --name "$name" --with-decryption --region "$REGION" \
    --query 'Parameter.Value' --output text)

  aws ssm put-parameter \
    --name "/piki-core/$DST_ENV/$key" \
    --type "$ptype" \
    --value "$value" \
    --overwrite \
    --region "$REGION" > /dev/null

  unset value
  copied=$((copied + 1))
  echo "  [copy] $key ($ptype)"
done

# db-host — 부하 DB 박스 사설 IP. 원본과 같은 SecureString 으로 둔다(앱은 타입을 가리지 않지만
# 경로 전체의 취급을 일관되게 유지한다).
aws ssm put-parameter \
  --name "/piki-core/$DST_ENV/db-host" \
  --type SecureString \
  --value "$DB_HOST" \
  --overwrite \
  --region "$REGION" > /dev/null
echo "  [set ] db-host (SecureString) -> 부하 DB 박스"

echo "[ssm] 완료 — 복제 $copied 건, db-host 1 건 재지정 (원본에서 건너뛴 항목 $skipped)"
echo "[ssm] 확인: aws ssm get-parameters-by-path --path /piki-core/$DST_ENV/ --recursive --query 'length(Parameters)'"
