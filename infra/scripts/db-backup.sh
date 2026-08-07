#!/usr/bin/env bash
# DB 박스 논리 백업 — mysqldump 를 gzip 해 S3 에 올린다 (#898).
#
# cron 이 하루 한 번 호출한다(설치는 provision-db.sh). RDS 를 걷어내면서 사라진 관리형 백업을
# 대신하는 유일한 복구 경로이므로, 실패를 조용히 넘기지 않고 로그에 남기고 비정상 종료한다.
#
# --single-transaction 이 핵심이다. InnoDB 의 일관된 스냅샷을 트랜잭션으로 떠서 테이블 락을
# 걸지 않으므로, 백업 중에도 서비스는 그대로 읽고 쓴다.
set -euo pipefail

AWSCLI_IMAGE="public.ecr.aws/aws-cli/aws-cli:2.35.21"
CONTAINER="piki-prod-mysql"
REGION="ap-northeast-2"
SSM_PREFIX="/piki-core/prod"
WORK_DIR="/var/tmp/piki-db-backup"

log() { echo "[db-backup] $(date -u +%Y-%m-%dT%H:%M:%SZ) $*"; }

ssm_param() {
  docker run --rm --network host "$AWSCLI_IMAGE" ssm get-parameter \
    --name "${SSM_PREFIX}/$1" --with-decryption \
    --region "$REGION" --query Parameter.Value --output text
}

DB_NAME="$(ssm_param db-name)" || { log "SSM db-name 조회 실패"; exit 1; }
# 덤프는 root 로 뜬다(전 스키마 접근). root 비밀번호는 앱 계정(db-password)과 분리돼 있으므로
# 여기서 db-root-password 를 읽는다 — 앱 자격증명이 새도 백업 경로의 권한은 함께 넘어가지 않는다.
DB_ROOT_PASSWORD="$(ssm_param db-root-password)" || { log "SSM db-root-password 조회 실패"; exit 1; }
BUCKET="$(ssm_param db-backup-bucket)" || { log "SSM db-backup-bucket 조회 실패"; exit 1; }

TS="$(date -u +%Y%m%dT%H%M%SZ)"
FILE="${DB_NAME}-${TS}.sql.gz"
mkdir -p "$WORK_DIR"

# 덤프 → gzip. root 로 뜬다(스키마 전체와 routine·trigger 까지 읽어야 한다).
# --databases 를 쓰면 CREATE DATABASE / USE 문이 함께 담겨 빈 서버에 그대로 복원된다.
# --routines --triggers --events: 스키마 외 객체까지 포함해 "이 파일 하나면 복구된다"를 지킨다.
#
# 파이프 중간(mysqldump)의 실패를 놓치지 않도록 set -o pipefail 이 위에서 켜져 있다 —
# 이게 없으면 덤프가 깨져도 gzip 성공 코드만 보고 손상된 파일을 업로드한다.
log "덤프 시작: ${DB_NAME}"
if ! docker exec -e MYSQL_PWD="$DB_ROOT_PASSWORD" "$CONTAINER" \
  mysqldump -u root --single-transaction --routines --triggers --events \
  --databases "$DB_NAME" 2>"${WORK_DIR}/dump.err" | gzip > "${WORK_DIR}/${FILE}"; then
  log "덤프 실패: $(tail -3 "${WORK_DIR}/dump.err")"
  rm -f "${WORK_DIR}/${FILE}"
  exit 1
fi

SIZE="$(stat -c %s "${WORK_DIR}/${FILE}")"
# 빈 파일·헤더만 있는 산출물이 "성공"으로 올라가 복구 시점에야 발각되는 걸 막는다.
# 정상 덤프는 gzip 후에도 최소 수 KB 다.
if [ "$SIZE" -lt 1024 ]; then
  log "덤프 산출물이 비정상적으로 작다(${SIZE} bytes) — 업로드 중단"
  rm -f "${WORK_DIR}/${FILE}"
  exit 1
fi

log "업로드: s3://${BUCKET}/${FILE} (${SIZE} bytes)"
if ! docker run --rm --network host -v "${WORK_DIR}:/backup" "$AWSCLI_IMAGE" \
  s3 cp "/backup/${FILE}" "s3://${BUCKET}/${FILE}" --region "$REGION" > /dev/null; then
  log "S3 업로드 실패 — 로컬 파일은 남겨 둔다(${WORK_DIR}/${FILE})"
  exit 1
fi

# 업로드가 끝난 로컬 사본은 지운다. 루트 볼륨이 10GB 라 쌓이면 곧 찬다.
# (실패 시엔 위에서 남겨 두고 종료해 수동 회수 여지를 준다.)
rm -f "${WORK_DIR}/${FILE}" "${WORK_DIR}/dump.err"
log "완료"
