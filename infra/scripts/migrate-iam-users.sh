#!/usr/bin/env bash
# 개발자 IAM 유저 이관 (#1092). GitHub 러너가 구 계정에서 그룹·멤버·정책을 읽어 신 계정에 재생성한다.
#
# 무엇을 옮기나 (구조):
#   - 그룹(GROUP_NAME, 기본 PiKi-Developer) + 그 그룹의 관리형·인라인 정책
#   - 그 그룹의 멤버 유저 + 각 유저의 관리형·인라인 정책 + 그룹 소속
#   - 각 유저의 콘솔 로그인 프로필: 디폴트 비번 + 최초 로그인 시 강제 변경(--password-reset-required)
#
# 무엇을 안 옮기나 (credential — AWS 가 계정 간 복사를 금지):
#   - 콘솔 비밀번호(디폴트로 새로 발급) · MFA · 액세스 키. 액세스 키는 각 유저가 로그인 후 직접 만든다.
#
# 필터: 그룹 멤버만 복제하므로 서비스 유저(offway-*·piki-migration, 그룹 없음)는 자동 제외된다.
#
# 자격: 구 계정 읽기는 SRC 키(장기 키라 AWS_SESSION_TOKEN 을 비운다). 신 계정 쓰기는 러너의 주변 환경
#       자격(워크플로가 OIDC 로 맡은 역할)으로 한다. 멱등 — 이미 있으면 건너뛰거나 갱신한다.
set -euo pipefail

GROUP_NAME="${GROUP_NAME:-PiKi-Developer}"
INITIAL_PASSWORD="${INITIAL_PASSWORD:?신 계정 로그인 디폴트 비번 (신 계정 password policy 를 만족해야 함)}"
AWS_REGION="${AWS_REGION:-ap-northeast-2}"
SRC_AWS_KEY="${SRC_AWS_KEY:?구 계정 액세스 키}"
SRC_AWS_SECRET="${SRC_AWS_SECRET:?구 계정 시크릿}"

log() { echo "[migrate-iam] $(date -u +%H:%M:%S) $*"; }

# 구 계정 읽기 — 장기 키라 세션토큰을 비운다(주변 OIDC 세션토큰이 섞이면 InvalidClientTokenId).
src() { env -u AWS_SESSION_TOKEN "AWS_ACCESS_KEY_ID=$SRC_AWS_KEY" "AWS_SECRET_ACCESS_KEY=$SRC_AWS_SECRET" "AWS_DEFAULT_REGION=$AWS_REGION" aws "$@"; }
# 신 계정 쓰기 — 주변 환경(OIDC 로 맡은 역할).
dst() { aws --region "$AWS_REGION" "$@"; }

# 관리형 정책 하나를 신 계정에 준비하고 그 신 계정용 ARN 을 stdout 으로 돌려준다.
#   - AWS 관리형(arn:aws:iam::aws:policy/…)은 모든 계정 공통이라 그대로 쓴다.
#   - 고객 관리형(계정번호 박힌 ARN)은 신 계정에 없으므로 문서를 읽어 같은 이름으로 만든 뒤 신 ARN 을 쓴다.
ensure_policy() {
  local arn="$1" name doc newarn
  case "$arn" in
    arn:aws:iam::aws:policy/*) printf '%s' "$arn"; return 0 ;;
  esac
  name="${arn##*/}"
  newarn=$(dst iam list-policies --scope Local --query "Policies[?PolicyName=='$name'].Arn" --output text)
  if [ -z "$newarn" ] || [ "$newarn" = "None" ]; then
    local ver
    ver=$(src iam get-policy --policy-arn "$arn" --query 'Policy.DefaultVersionId' --output text)
    doc=$(src iam get-policy-version --policy-arn "$arn" --version-id "$ver" --query 'PolicyVersion.Document' --output json)
    newarn=$(dst iam create-policy --policy-name "$name" --policy-document "$doc" --query 'Policy.Arn' --output text)
    log "  고객 관리형 정책 재생성 — $name"
  fi
  printf '%s' "$newarn"
}

# ── 그룹 ─────────────────────────────────────────────────────────────────────
dst iam get-group --group-name "$GROUP_NAME" >/dev/null 2>&1 || {
  dst iam create-group --group-name "$GROUP_NAME" >/dev/null
  log "그룹 생성 — $GROUP_NAME"
}
# 그룹 관리형 정책
for arn in $(src iam list-attached-group-policies --group-name "$GROUP_NAME" --query 'AttachedPolicies[].PolicyArn' --output text); do
  newarn=$(ensure_policy "$arn")
  dst iam attach-group-policy --group-name "$GROUP_NAME" --policy-arn "$newarn"
done
# 그룹 인라인 정책
for p in $(src iam list-group-policies --group-name "$GROUP_NAME" --query 'PolicyNames[]' --output text); do
  doc=$(src iam get-group-policy --group-name "$GROUP_NAME" --policy-name "$p" --query 'PolicyDocument' --output json)
  dst iam put-group-policy --group-name "$GROUP_NAME" --policy-name "$p" --policy-document "$doc"
done
log "그룹 정책 반영 완료 — $GROUP_NAME"

# ── 멤버 ─────────────────────────────────────────────────────────────────────
MEMBERS=$(src iam get-group --group-name "$GROUP_NAME" --query 'Users[].UserName' --output text)
[ -n "$MEMBERS" ] || { log "그룹 멤버 없음 — $GROUP_NAME"; exit 0; }
COUNT=0
for u in $MEMBERS; do
  dst iam get-user --user-name "$u" >/dev/null 2>&1 || {
    dst iam create-user --user-name "$u" >/dev/null
    log "유저 생성 — $u"
  }
  dst iam add-user-to-group --group-name "$GROUP_NAME" --user-name "$u"
  # 유저 관리형 정책
  for arn in $(src iam list-attached-user-policies --user-name "$u" --query 'AttachedPolicies[].PolicyArn' --output text); do
    newarn=$(ensure_policy "$arn")
    dst iam attach-user-policy --user-name "$u" --policy-arn "$newarn"
  done
  # 유저 인라인 정책
  for p in $(src iam list-user-policies --user-name "$u" --query 'PolicyNames[]' --output text); do
    doc=$(src iam get-user-policy --user-name "$u" --policy-name "$p" --query 'PolicyDocument' --output json)
    dst iam put-user-policy --user-name "$u" --policy-name "$p" --policy-document "$doc"
  done
  # 콘솔 로그인 프로필 — 디폴트 비번 + 최초 로그인 시 강제 변경. 이미 있으면 그대로 둔다(비번 덮어쓰지 않음).
  if dst iam get-login-profile --user-name "$u" >/dev/null 2>&1; then
    log "로그인 프로필 이미 있음 — $u (비번 유지)"
  else
    dst iam create-login-profile --user-name "$u" --password "$INITIAL_PASSWORD" --password-reset-required >/dev/null
    log "로그인 프로필 생성 — $u (디폴트 비번, 최초 로그인 시 변경 강제)"
  fi
  COUNT=$((COUNT + 1))
done
log "완료 — $GROUP_NAME 멤버 $COUNT 명 재생성. 각 유저는 로그인 후 MFA·액세스키를 직접 등록한다(credential 은 이관 불가)."
