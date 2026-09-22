#!/usr/bin/env bash
# freeze 롤백 — migrate-db.sh 가 정지시킨 구 계정 앱 컨테이너를 되살린다 (#1092).
#
# migrate-db.sh 의 cutover 는 DB 파이프라인까지만 책임진다. 그 뒤에도 S3 델타 sync·스모크·DNS
# 전환이 남아 있고, 그 사이 어디서 실패해도 구 앱은 정지 상태다. 롤백 경계가 스크립트에서 끊기면
# 운영 서비스가 내려간 채 방치되므로, 워크플로가 이 스크립트를 trap 으로 걸어 경계를 이어받는다.
#
#   trap 'bash infra/scripts/migrate-unfreeze.sh' ERR
#   ... S3 sync · 스모크 · DNS 전환 ...
#   rm -f "$FREEZE_STATE_FILE"   # DNS 가 넘어간 뒤 무장해제 — 성공한 컷오버는 구 앱이 꺼진 채여야 한다
#
# 무장해제를 파일 삭제로 하는 이유: trap 해제는 그 셸 안에서만 유효한데, 워크플로는 스텝마다
# 셸이 갈린다. 상태를 파일에 두면 어느 스텝에서 불려도 같은 판단을 한다.
set -uo pipefail

FREEZE_STATE_FILE="${FREEZE_STATE_FILE:-/tmp/piki-migrate-frozen}"
SRC_APP_HOST="${SRC_APP_HOST:?구 계정 앱 박스 호스트}"
SRC_APP_KEY="${SRC_APP_KEY:?구 계정 앱 박스 SSH 키파일}"

log() { echo "[migrate-unfreeze] $(date -u +%H:%M:%S) $*"; }

# 파일이 없거나 비었으면 되살릴 게 없다 — 이미 무장해제됐거나 애초에 freeze 가 없었다.
# 이 경로는 실패 처리 중에 불리므로 여기서 다시 죽지 않게 항상 0 으로 끝난다.
[ -s "$FREEZE_STATE_FILE" ] || { log "복구 대상 없음 — 생략"; exit 0; }

log "구 앱 컨테이너 재기동: $(tr '\n' ' ' < "$FREEZE_STATE_FILE")"
if ssh -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=/dev/null -o ConnectTimeout=15 \
     -i "$SRC_APP_KEY" "ubuntu@$SRC_APP_HOST" 'xargs -r docker start' < "$FREEZE_STATE_FILE"; then
  log "롤백 완료 — 구 계정 서비스 복구"
  echo "::notice::freeze 롤백 완료 — 구 계정 앱을 되살렸다. DNS 는 아직 구 계정을 가리킨다."
  exit 0
else
  log "롤백 실패"
  echo "::error::freeze 롤백 실패 — 구 계정 박스($SRC_APP_HOST)에서 직접 docker start 해야 한다: $(tr '\n' ' ' < "$FREEZE_STATE_FILE")"
  # 0 으로 끝내지 않는다. 이 경로는 "구 서비스가 내려간 채 남았다" 는 뜻인데, 0 을 돌려주면 호출한
  # 스텝이 초록불이 되고 컷오버 실패 안내가 "롤백이 돌아 구 서비스는 복구됐다" 고 단정해 버린다.
  # 상태 파일은 남겨 둔다 — 재실행·재시도가 같은 컨테이너 목록을 다시 집어야 하기 때문이다.
  exit 1
fi
