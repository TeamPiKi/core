#!/usr/bin/env bash
# 신 계정에 GitHub Actions 발판을 놓는다 — 사람이 CloudShell 에 한 번 붙여넣는 유일한 스크립트 (#1092).
#
# ── 왜 사람이 해야 하나 ──
#
# 갓 만든 AWS 계정에는 GitHub 이 붙을 접점이 하나도 없다. OIDC provider 도, 역할도, 키도 없다.
# 구 계정의 어떤 자격증명도 신 계정에 IAM 리소스를 만들 권한이 없으므로(별개 계정), 첫 발판만은
# 그 계정 안에서 사람이 놓아야 한다. 이 스크립트가 그 한 번이다.
#
# ── 왜 액세스 키가 아니라 OIDC 인가 ──
#
# 예전 방식은 콘솔에서 IAM 사용자를 만들고 액세스 키 2개를 GitHub secret 에 붙여넣은 뒤, 이관이
# 끝나면 지우는 것이었다. 사람이 하는 일이 4단계로 늘고, 마지막 "지우기" 가 빠지면 장기 자격증명이
# 계정에 남는다(#808 의 F-35 가 정확히 그렇게 미완으로 남았다).
#
# OIDC 는 붙여넣기 한 번으로 끝나고, 남는 것이 없다. GitHub 이 실행할 때마다 단기 토큰을 받아
# 역할을 맡는다. 지울 키가 애초에 없다.
#
# ── 실행 방법 ──
#
#   신 계정 AWS 콘솔 → 우측 상단 CloudShell → 아래 내용을 통째로 붙여넣고 Enter
#
# 멱등하다. 두 번 돌려도 같은 상태로 수렴하므로, 실패했거나 확신이 안 서면 그냥 다시 붙여넣으면 된다.
# CloudShell 은 이 스크립트를 "파일 실행" 이 아니라 "붙여넣어 현재 셸에서 실행" 하는 방식이라,
# set -e 가 걸린 채 명령이 실패하면 로그인 셸 자체가 죽고 RECOVERY MODE 로 빠진다. 그 뒤엔 변수가
# 전부 날아가 남은 줄들이 빈 값으로 돌며 엉뚱한 성공 메시지를 낸다(실측). 그래서 -e 를 쓰지 않고
# 각 단계가 자기 실패를 직접 보고하게 한다.
set -uo pipefail

REPO="${REPO:-TeamPiKi/core}"
ROLE_NAME="${ROLE_NAME:-piki-migrate-bootstrap}"
PROVIDER_HOST="token.actions.githubusercontent.com"

ACCOUNT="$(aws sts get-caller-identity --query Account --output text)"
PROVIDER_ARN="arn:aws:iam::${ACCOUNT}:oidc-provider/${PROVIDER_HOST}"

echo "[bootstrap-oidc] 계정 ${ACCOUNT} 에 ${REPO} 용 발판을 놓는다"

# ── 1. OIDC provider ─────────────────────────────────────────────────────────
# thumbprint 는 지금의 AWS 가 검증에 쓰지 않지만(내부 신뢰 저장소를 본다) API 가 값을 요구한다.
# GitHub 이 공개한 중간 CA 지문을 그대로 넣는다.
if aws iam get-open-id-connect-provider --open-id-connect-provider-arn "$PROVIDER_ARN" >/dev/null 2>&1; then
  echo "[bootstrap-oidc] OIDC provider 이미 존재 — 건너뜀"
else
  aws iam create-open-id-connect-provider \
    --url "https://${PROVIDER_HOST}" \
    --client-id-list "sts.amazonaws.com" \
    --thumbprint-list "6938fd4d98bab03faadb97b34396831e3780aea1" >/dev/null
  echo "[bootstrap-oidc] OIDC provider 생성"
fi

# ── 2. 이관용 역할 ───────────────────────────────────────────────────────────
# sub 에 세 레포를 둔다 — 이관이 core 뿐 아니라 extractor·renderer 배포도 신 계정 대상으로 돌리는데,
# 그 배포들은 각자 레포에서 이 역할을 assume 해 ECR 에 push 한다. core 만 허용하면 그쪽이
# "Not authorized to perform sts:AssumeRoleWithWebIdentity" 로 죽는다(실측).
# sub 를 repo:<owner>/<repo>:* 로 둔다. 이 워크플로는 브랜치·환경을 가리지 않고 도는 데다,
# 실행 자체가 workflow_dispatch + confirm=MIGRATE + 계정번호 대조로 이미 좁혀져 있다.
# aud 조건은 반드시 건다 — 없으면 다른 OIDC 발급자의 토큰까지 받아들일 여지가 생긴다.
TRUST=$(cat <<JSON
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Federated": "${PROVIDER_ARN}" },
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": { "${PROVIDER_HOST}:aud": "sts.amazonaws.com" },
      "StringLike": { "${PROVIDER_HOST}:sub": [
        "repo:${REPO}:*",
        "repo:TeamPiKi/extractor:*",
        "repo:TeamPiKi/renderer:*"
      ] }
    }
  }]
}
JSON
)

if aws iam get-role --role-name "$ROLE_NAME" >/dev/null 2>&1; then
  aws iam update-assume-role-policy --role-name "$ROLE_NAME" --policy-document "$TRUST"
  echo "[bootstrap-oidc] 역할 이미 존재 — 신뢰 정책만 갱신"
else
  if aws iam create-role --role-name "$ROLE_NAME" \
    --description "GitHub Actions account migration role (#1092)" \
    --assume-role-policy-document "$TRUST" >/dev/null; then
    echo "[bootstrap-oidc] 역할 생성 — $ROLE_NAME"
  else
    echo "[bootstrap-oidc] 역할 생성 실패 — 위 오류를 확인할 것" >&2
    return 1 2>/dev/null || exit 1
  fi
fi

# 권한은 AdministratorAccess 다. 이 역할이 하는 일이 곧 "빈 계정에 인프라를 통째로 세우는 것" —
# VPC·EC2·S3·IAM·SSM 을 전부 만든다. 최소권한으로 좁히려면 terraform 이 건드리는 모든 리소스를
# 열거해야 하고, 그 목록은 terraform 이 바뀔 때마다 조용히 어긋난다.
#
# 대신 수명으로 좁힌다 — 이 역할은 이관이 끝나면 쓸 일이 없다. finalize 가 그 사실을 알린다.
aws iam attach-role-policy --role-name "$ROLE_NAME" \
  --policy-arn "arn:aws:iam::aws:policy/AdministratorAccess"

echo
echo "[bootstrap-oidc] 완료 — GitHub 이 이 계정에 붙을 수 있다"
echo "[bootstrap-oidc] 역할: arn:aws:iam::${ACCOUNT}:role/${ROLE_NAME}"
echo "[bootstrap-oidc] 워크플로가 자동으로 감지해 이어서 진행한다. 이 창은 닫아도 된다."
