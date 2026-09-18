#!/usr/bin/env bash
# 개발자 IAM 유저 이관 (#1092). GitHub 러너가 구 계정에서 그룹·멤버·정책을 읽어 신 계정에 재생성한다.
#
# 무엇을 옮기나 (구조):
#   - 그룹(GROUP_NAME, 기본 PiKi-Developer) + 그 그룹의 관리형·인라인 정책
#   - 그 그룹의 멤버 유저 + 각 유저의 관리형·인라인 정책 + 그룹 소속
#   - 각 유저 콘솔 로그인 프로필: 유저별 랜덤 임시 비번 + 최초 로그인 시 강제 변경(--password-reset-required)
#     생성한 임시 비번은 Discord 로만 전송하고(유저별 한 줄), 로그·stdout 에는 절대 찍지 않는다.
#
# 무엇을 안 옮기나 (credential — AWS 가 계정 간 복사를 금지):
#   - 콘솔 비밀번호(임시로 새로 발급) · MFA · 액세스 키. 액세스 키는 각 유저가 로그인 후 직접 만든다.
#
# 필터: 그룹 멤버만 복제하므로 서비스 유저(offway-*·piki-migration, 그룹 없음)는 자동 제외된다.
#
# 자격: 구 계정 읽기는 SRC 키(장기 키라 AWS_SESSION_TOKEN 을 비운다). 신 계정 쓰기는 러너의 주변 환경
#       자격(워크플로가 OIDC 로 맡은 역할)으로 한다. 멱등 — 이미 있으면 건너뛰거나 갱신한다.
set -euo pipefail

GROUP_NAME="${GROUP_NAME:-PiKi-Developer}"
AWS_REGION="${AWS_REGION:-ap-northeast-2}"
SRC_AWS_KEY="${SRC_AWS_KEY:?구 계정 액세스 키}"
SRC_AWS_SECRET="${SRC_AWS_SECRET:?구 계정 시크릿}"
# 발급한 임시 비번은 채널이 아니라 admin DM 으로만 보낸다 — webhook 은 채널이라 모두가 남의 비번까지 본다.
# 봇이 ADMIN_DISCORD_ID 에게 DM(admin 진입 경로와 같은 결). 둘 중 하나라도 없으면 전송 생략(콘솔에서 재설정).
DISCORD_BOT_TOKEN="${DISCORD_BOT_TOKEN:-}"
ADMIN_DISCORD_ID="${ADMIN_DISCORD_ID:-}"

# stderr 로 보낸다 — ensure_policy 의 stdout 은 $(...) 로 캡처돼 정책 ARN 으로 쓰이므로, 로그가 섞이면 ARN 이 깨진다.
log() { echo "[migrate-iam] $(date -u +%H:%M:%S) $*" >&2; }

# 정책 문서 두 개가 의미상 같은지(키 순서·포맷 무시) 비교.
docs_equal() { python3 -c 'import json,sys; sys.exit(0 if json.dumps(json.loads(sys.argv[1]),sort_keys=True)==json.dumps(json.loads(sys.argv[2]),sort_keys=True) else 1)' "$1" "$2"; }

# 구 계정 읽기 — 장기 키라 세션토큰을 비운다(주변 OIDC 세션토큰이 섞이면 InvalidClientTokenId).
src() { env -u AWS_SESSION_TOKEN "AWS_ACCESS_KEY_ID=$SRC_AWS_KEY" "AWS_SECRET_ACCESS_KEY=$SRC_AWS_SECRET" "AWS_DEFAULT_REGION=$AWS_REGION" aws "$@"; }
# 신 계정 쓰기 — 주변 환경(OIDC 로 맡은 역할).
dst() { aws --region "$AWS_REGION" "$@"; }

# 유저별 임시 비번. 대문자(P)·소문자·숫자(1)·기호(-·!)를 항상 포함해 어지간한 password policy 를 만족한다.
gen_pw() { echo "PiKi-$(LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 12)!1"; }

# 관리형 정책 하나를 신 계정에 준비하고 그 신 계정용 ARN 을 stdout 으로 돌려준다.
#   - AWS 관리형(arn:aws:iam::aws:policy/…)은 모든 계정 공통이라 그대로 쓴다.
#   - 고객 관리형(계정번호 박힌 ARN)은 신 계정에 없으므로 문서를 읽어 같은 이름으로 만든 뒤 신 ARN 을 쓴다.
ensure_policy() {
  local arn="$1" name newarn ver srcdoc dstver dstdoc v
  case "$arn" in
    arn:aws:iam::aws:policy/*) printf '%s' "$arn"; return 0 ;;
  esac
  name="${arn##*/}"
  ver=$(src iam get-policy --policy-arn "$arn" --query 'Policy.DefaultVersionId' --output text)
  srcdoc=$(src iam get-policy-version --policy-arn "$arn" --version-id "$ver" --query 'PolicyVersion.Document' --output json)
  newarn=$(dst iam list-policies --scope Local --query "Policies[?PolicyName=='$name'].Arn" --output text)
  if [ -z "$newarn" ] || [ "$newarn" = "None" ]; then
    newarn=$(dst iam create-policy --policy-name "$name" --policy-document "$srcdoc" --query 'Policy.Arn' --output text)
    log "  고객 관리형 정책 재생성 — $name"
  else
    # 이미 있으면 문서 드리프트를 확인해 원본에 맞춘다(멱등 계약 '갱신' — 재실행·부분 이관 시 낡은 권한 잔존 방지).
    dstver=$(dst iam get-policy --policy-arn "$newarn" --query 'Policy.DefaultVersionId' --output text)
    dstdoc=$(dst iam get-policy-version --policy-arn "$newarn" --version-id "$dstver" --query 'PolicyVersion.Document' --output json)
    if ! docs_equal "$srcdoc" "$dstdoc"; then
      # 정책 버전 5개 상한 대비 — 비기본 버전을 지운 뒤 새 기본 버전을 만든다.
      for v in $(dst iam list-policy-versions --policy-arn "$newarn" --query 'Versions[?IsDefaultVersion==`false`].VersionId' --output text); do
        dst iam delete-policy-version --policy-arn "$newarn" --version-id "$v" || true
      done
      dst iam create-policy-version --policy-arn "$newarn" --policy-document "$srcdoc" --set-as-default >/dev/null
      log "  고객 관리형 정책 문서 동기화 — $name"
    fi
  fi
  printf '%s' "$newarn"
}

# ── 그룹 ─────────────────────────────────────────────────────────────────────
dst iam get-group --group-name "$GROUP_NAME" >/dev/null 2>&1 || {
  dst iam create-group --group-name "$GROUP_NAME" >/dev/null
  log "그룹 생성 — $GROUP_NAME"
}
for arn in $(src iam list-attached-group-policies --group-name "$GROUP_NAME" --query 'AttachedPolicies[].PolicyArn' --output text); do
  dst iam attach-group-policy --group-name "$GROUP_NAME" --policy-arn "$(ensure_policy "$arn")"
done
for p in $(src iam list-group-policies --group-name "$GROUP_NAME" --query 'PolicyNames[]' --output text); do
  doc=$(src iam get-group-policy --group-name "$GROUP_NAME" --policy-name "$p" --query 'PolicyDocument' --output json)
  dst iam put-group-policy --group-name "$GROUP_NAME" --policy-name "$p" --policy-document "$doc"
done
log "그룹 정책 반영 완료 — $GROUP_NAME"

# ── 멤버 ─────────────────────────────────────────────────────────────────────
MEMBERS=$(src iam get-group --group-name "$GROUP_NAME" --query 'Users[].UserName' --output text)
[ -n "$MEMBERS" ] || { log "그룹 멤버 없음 — $GROUP_NAME"; exit 0; }
NEW_CREDS=""  # 새로 발급한 임시 비번 목록 — Discord 로만 나가고 로그엔 안 찍는다
COUNT=0
for u in $MEMBERS; do
  dst iam get-user --user-name "$u" >/dev/null 2>&1 || {
    dst iam create-user --user-name "$u" >/dev/null
    log "유저 생성 — $u"
  }
  dst iam add-user-to-group --group-name "$GROUP_NAME" --user-name "$u"
  # PermissionsBoundary 동기화 — 원본에 있으면 같은 걸 걸고(없으면 대상서 제거), 상태를 일치시킨다.
  # 안 하면 원본에서 경계로 좁혀둔 유저가 신 계정에선 경계 없이 더 넓은 권한을 갖는다.
  BOUNDARY=$(src iam get-user --user-name "$u" --query 'User.PermissionsBoundary.PermissionsBoundaryArn' --output text 2>/dev/null || echo None)
  if [ -n "$BOUNDARY" ] && [ "$BOUNDARY" != "None" ]; then
    dst iam put-user-permissions-boundary --user-name "$u" --permissions-boundary "$(ensure_policy "$BOUNDARY")"
  else
    dst iam delete-user-permissions-boundary --user-name "$u" >/dev/null 2>&1 || true
  fi
  for arn in $(src iam list-attached-user-policies --user-name "$u" --query 'AttachedPolicies[].PolicyArn' --output text); do
    dst iam attach-user-policy --user-name "$u" --policy-arn "$(ensure_policy "$arn")"
  done
  for p in $(src iam list-user-policies --user-name "$u" --query 'PolicyNames[]' --output text); do
    doc=$(src iam get-user-policy --user-name "$u" --policy-name "$p" --query 'PolicyDocument' --output json)
    dst iam put-user-policy --user-name "$u" --policy-name "$p" --policy-document "$doc"
  done
  # 콘솔 로그인 프로필 — 유저별 랜덤 임시 비번 + 강제 변경. 이미 있으면 안 건드린다(재실행 시 비번 재발급 방지).
  if dst iam get-login-profile --user-name "$u" >/dev/null 2>&1; then
    log "로그인 프로필 이미 있음 — $u (비번 유지)"
  else
    PW="$(gen_pw)"
    dst iam create-login-profile --user-name "$u" --password "$PW" --password-reset-required >/dev/null
    NEW_CREDS="$NEW_CREDS"$'\n'"$u    $PW"
    log "로그인 프로필 생성 — $u (임시 비번 발급, 최초 로그인 시 변경 강제)"
  fi
  COUNT=$((COUNT + 1))
done

# ── 발급한 임시 비번을 admin DM 으로만 전달 (채널 노출 방지·로그엔 안 남긴다) ─────
if [ -n "$NEW_CREDS" ]; then
  if [ -n "$DISCORD_BOT_TOKEN" ] && [ -n "$ADMIN_DISCORD_ID" ]; then
    ACCOUNT=$(dst sts get-caller-identity --query Account --output text)
    CONTENT="**$GROUP_NAME 초기 자격 (첫 로그인 시 비밀번호 변경 필수)**"$'\n'"콘솔: https://$ACCOUNT.signin.aws.amazon.com/console"$'\n'"---$NEW_CREDS"
    # 봇으로 admin 과의 DM 채널을 열고(그 채널로만), 거기에 비번 목록을 보낸다. 채널 게시 아님.
    DM_ID=$(curl -sf -X POST -H "Authorization: Bot $DISCORD_BOT_TOKEN" -H 'Content-Type: application/json' \
      -d "{\"recipient_id\":\"$ADMIN_DISCORD_ID\"}" https://discord.com/api/v10/users/@me/channels \
      | python3 -c 'import sys,json; print(json.load(sys.stdin).get("id",""))' 2>/dev/null || true)
    if [ -n "$DM_ID" ]; then
      BODY=$(CONTENT="$CONTENT" python3 -c 'import json,os; print(json.dumps({"content": os.environ["CONTENT"]}))')
      curl -sf -X POST -H "Authorization: Bot $DISCORD_BOT_TOKEN" -H 'Content-Type: application/json' \
        -d "$BODY" "https://discord.com/api/v10/channels/$DM_ID/messages" >/dev/null \
        && log "임시 비번을 admin DM 으로 전송했다(채널 노출 없음)" \
        || log "::warning::admin DM 전송 실패 — 콘솔에서 각 유저 비번을 재설정해야 한다"
    else
      log "::warning::admin DM 채널 개설 실패(봇 권한·ADMIN_DISCORD_ID 확인) — 콘솔에서 재설정 필요"
    fi
  else
    log "::warning::DISCORD_BOT_TOKEN/ADMIN_DISCORD_ID 미설정 — 임시 비번을 DM 으로 못 보낸다. 콘솔에서 각 유저 비번을 재설정할 것"
  fi
fi
log "완료 — $GROUP_NAME 멤버 $COUNT 명 재생성. 각 유저는 로그인 후 MFA·액세스키를 직접 등록한다(credential 은 이관 불가)."
