#!/usr/bin/env bash
# 계정 이관 DB 파이프라인 (#1092). GitHub 러너가 구 계정·신 계정의 DB 박스를 원격 조종한다.
#
#   rehearsal: 무중단(--single-transaction) 덤프 → 신 계정 복원 → 이미지 URL 재작성 → 행수 대조. 라이브 무영향.
#   cutover:   freeze(구 앱 정지)로 쓰기 차단 후 rehearsal 과 같은 파이프라인. 유일한 다운타임 구간.
#
# ── 왜 "박스 안에서 실행 + S3 릴레이" 인가 ──
#
# 처음엔 러너가 ssh 로 mysqldump 출력을 직접 스트리밍해 받는 구조였다. prod 에서 성립하지 않는다.
# infra/compose/db.yml 이 MYSQL_ROOT_HOST=localhost 로 두어 root 는 컨테이너 안에서만 접속되고,
# 그 컨테이너는 앱 박스가 아니라 별도 DB 박스(piki-prod-db)에 있다. 앱 박스 EIP 로 docker exec 를
# 부를 수도, 네트워크 너머에서 root 로 붙을 수도 없다.
#
# 그래서 db-backup.sh 가 이미 검증해 둔 모양을 그대로 쓴다 — 명령을 박스 안에서 돌리고 산출물은
# S3 로 옮긴다. 딸려 오는 이득이 크다:
#
#   - root 비밀번호를 아무 데도 넘기지 않는다. 박스가 자기 인스턴스 role 로 SSM 에서 직접 읽는다.
#     러너의 ps·원격 ps·명령 이력 어디에도 비밀번호가 뜨지 않고, 따옴표가 섞인 비번에도 안 깨진다.
#   - 덤프가 러너 디스크·SSH 세션을 통과하지 않는다. prod 규모에서 긴 스트리밍이 끊길 자리가 없다.
#   - dev(SSH)·prod(SSM)의 차이가 "명령을 어떻게 던지나" 한 곳(remote_exec)으로 모인다.
#
# 두 계정 버킷 사이엔 cross-account 정책이 없으므로 릴레이(다운로드→업로드)는 러너가 한다.
set -euo pipefail

MODE="${MODE:?rehearsal|cutover 를 지정}"
DB_NAME="${DB_NAME:?이관할 스키마명 (예: piki)}"
AWS_REGION="${AWS_REGION:-ap-northeast-2}"

# 양쪽 DB 박스를 어떻게 조종하나. ssh=키페어 있는 박스(dev 앱 박스 동거), ssm=키페어 없는 박스(prod DB 박스).
SRC_EXEC="${SRC_EXEC:?구 계정 DB 박스 조종 방식 (ssh|ssm)}"
DST_EXEC="${DST_EXEC:?신 계정 DB 박스 조종 방식 (ssh|ssm)}"
SRC_DB_CONTAINER="${SRC_DB_CONTAINER:?구 계정 MySQL 컨테이너명}"
DST_DB_CONTAINER="${DST_DB_CONTAINER:?신 계정 MySQL 컨테이너명}"
SRC_SSM_PREFIX="${SRC_SSM_PREFIX:?구 계정 SSM 프리픽스 (예: /piki-core/prod)}"
DST_SSM_PREFIX="${DST_SSM_PREFIX:?신 계정 SSM 프리픽스}"
SRC_RELAY_BUCKET="${SRC_RELAY_BUCKET:?구 계정 릴레이 버킷 (db_backup)}"
DST_RELAY_BUCKET="${DST_RELAY_BUCKET:?신 계정 릴레이 버킷 (db_backup)}"

# ssh 모드에서만 쓴다(빈 값 허용 — ssm 모드면 안 본다).
SRC_DB_HOST="${SRC_DB_HOST:-}"; SRC_DB_KEY="${SRC_DB_KEY:-}"
DST_DB_HOST="${DST_DB_HOST:-}"; DST_DB_KEY="${DST_DB_KEY:-}"
# ssm 모드에서만 쓴다. 대상 인스턴스의 Name 태그.
SRC_DB_TAG="${SRC_DB_TAG:-}"; DST_DB_TAG="${DST_DB_TAG:-}"

# 앱 박스 — freeze(구 앱 정지)와 롤백 대상. DB 박스와 다를 수 있다(prod). 키페어가 있어 항상 ssh.
SRC_APP_HOST="${SRC_APP_HOST:?구 계정 앱 박스 호스트}"
SRC_APP_KEY="${SRC_APP_KEY:?구 계정 앱 박스 SSH 키파일}"

OLD_IMAGE_BUCKET="${OLD_IMAGE_BUCKET:?구 이미지 버킷명}"
NEW_IMAGE_BUCKET="${NEW_IMAGE_BUCKET:?신 이미지 버킷명}"

# 구 계정 자격. 러너가 구 계정에 ssm send-command·s3 를 칠 때만 쓴다(박스 안 명령은 인스턴스 role 로 돈다).
#
# 신 계정 자격은 받지 않는다 — 호출측 워크플로가 OIDC 로 신 계정 역할을 맡은 상태라 러너의
# 주변 환경(AWS_ACCESS_KEY_ID/SECRET/SESSION_TOKEN)이 이미 신 계정이다. 여기서 또 받으면
# 같은 것을 두 경로로 관리하게 되고, 임시 자격의 SESSION_TOKEN 을 빠뜨리기 쉽다.
SRC_AWS_KEY="${SRC_AWS_KEY:?구 계정 액세스 키}"
SRC_AWS_SECRET="${SRC_AWS_SECRET:?구 계정 시크릿}"

# freeze 가 실제로 멈춘 컨테이너 목록. 워크플로의 후속 단계(S3 sync·스모크·DNS)까지 같은 롤백
# 경계를 공유하려고 파일로 남긴다 — migrate-unfreeze.sh 가 이 파일을 읽어 되살린다.
FREEZE_STATE_FILE="${FREEZE_STATE_FILE:-/tmp/piki-migrate-frozen}"

SSHOPT=(-o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=/dev/null -o ConnectTimeout=15)
AWSCLI_IMAGE="public.ecr.aws/aws-cli/aws-cli:2.35.21" # db-backup.sh 와 같은 핀. 박스에 aws CLI 가 없어도 된다.
RELAY_KEY="migrate/${DB_NAME}-$(date -u +%Y%m%dT%H%M%SZ).sql.gz"
WORK=/tmp/piki-migrate
mkdir -p "$WORK"

log() { echo "[migrate-db] $(date -u +%H:%M:%S) $*"; }

# ── 원격 실행 ────────────────────────────────────────────────────────────────
# 스크립트 본문을 stdin 으로 받아 해당 계정의 DB 박스에서 돌리고, 표준출력을 그대로 되돌린다.
# 본문을 인자가 아니라 stdin/base64 로 넘기는 이유: 명령줄에 실리면 원격 ps 와 SSM 명령 이력에
# 남고, 따옴표·개행이 섞이면 원격 셸이 재파싱하다 깨진다.
remote_exec() {
  local side="$1" body
  body="$(cat)"
  if [ "$side" = src ]; then
    [ "$SRC_EXEC" = ssh ] && { _exec_ssh "$SRC_DB_HOST" "$SRC_DB_KEY" "$body"; return; }
    _exec_ssm "$SRC_DB_TAG" "$SRC_AWS_KEY" "$SRC_AWS_SECRET" "$body"
  else
    [ "$DST_EXEC" = ssh ] && { _exec_ssh "$DST_DB_HOST" "$DST_DB_KEY" "$body"; return; }
    # 빈 자격 = 러너의 주변 환경을 쓴다(OIDC 로 맡은 신 계정 역할).
    _exec_ssm "$DST_DB_TAG" "" "" "$body"
  fi
}

_exec_ssh() {
  local host="$1" key="$2" body="$3"
  printf '%s' "$body" | ssh "${SSHOPT[@]}" -i "$key" "ubuntu@$host" 'bash -s'
}

# SSM Run Command 는 비동기다. CommandId 만 받고 끝내면 박스 안에서 실패해도 이 스크립트는
# 성공으로 넘어가고, 그 실패는 한참 뒤(복원·대조)에 엉뚱한 증상으로 드러난다. 반드시 대기하고
# 종료 상태를 전파한다.
_exec_ssm() {
  local tag="$1" akey="$2" asec="$3" body="$4" cmd_id instance_id status
  # 자격을 export 하지 않고 aws 호출마다 앞에 붙인다 — export 하면 같은 셸의 뒤 호출(반대 계정)까지
  # 물들어 엉뚱한 계정에 명령이 나간다. 키가 비면 주변 환경을 그대로 쓴다(신 계정 OIDC 역할).
  local -a A
  if [ -n "$akey" ]; then
    # 구 계정 자격은 장기 키라 SESSION_TOKEN 이 없다. 주변에 남아 있으면 키와 짝이 안 맞아
    # InvalidClientTokenId 가 나므로 명시적으로 비운다.
    A=(env "AWS_ACCESS_KEY_ID=$akey" "AWS_SECRET_ACCESS_KEY=$asec" --unset=AWS_SESSION_TOKEN "AWS_DEFAULT_REGION=$AWS_REGION" aws)
  else
    A=(env "AWS_DEFAULT_REGION=$AWS_REGION" aws)
  fi
  local b64; b64="$(printf '%s' "$body" | base64 -w0)"
  cmd_id=$("${A[@]}" ssm send-command \
    --targets "Key=tag:Name,Values=$tag" \
    --document-name AWS-RunShellScript \
    --comment "piki migrate-db ($MODE)" \
    --parameters commands="[\"set -e\",\"echo $b64 | base64 -d > /tmp/piki-migrate-remote.sh\",\"bash /tmp/piki-migrate-remote.sh\",\"rm -f /tmp/piki-migrate-remote.sh\"]" \
    --query 'Command.CommandId' --output text)
  # send-command 직후엔 invocation 이 아직 전파 안 돼 빈 결과가 올 수 있다. 나타날 때까지 잠깐 재조회한다 —
  # 첫 조회의 빈 결과를 "대상 없음"으로 오판하면 정상 명령이 실패로 찍힌다(prod SSM 경로).
  instance_id=None
  for _ in $(seq 1 30); do
    instance_id=$("${A[@]}" ssm list-command-invocations --command-id "$cmd_id" \
      --query 'CommandInvocations[0].InstanceId' --output text 2>/dev/null || echo None)
    { [ "$instance_id" != None ] && [ -n "$instance_id" ]; } && break
    sleep 2
  done
  { [ "$instance_id" != None ] && [ -n "$instance_id" ]; } || {
    log "SSM 대상 없음 — tag:Name=$tag 인스턴스가 없거나 SSM Agent 미등록"; return 1; }
  # wait 는 타임아웃(기본 약 10분)에 걸리면 실패 코드를 내지만, 그게 곧 명령 실패는 아니라
  # 판정은 아래 Status 로 한다. 긴 덤프를 넉넉히 기다리도록 재시도로 감싼다.
  local waited=0
  while [ "$waited" -lt 3600 ]; do
    status=$("${A[@]}" ssm get-command-invocation --command-id "$cmd_id" --instance-id "$instance_id" \
      --query Status --output text 2>/dev/null || echo Pending)
    case "$status" in
      Success|Failed|Cancelled|TimedOut) break ;;
    esac
    sleep 10; waited=$((waited + 10))
  done
  "${A[@]}" ssm get-command-invocation --command-id "$cmd_id" --instance-id "$instance_id" \
    --query StandardOutputContent --output text
  [ "$status" = Success ] || {
    log "SSM 실패 — Status=$status (tag=$tag)"
    "${A[@]}" ssm get-command-invocation --command-id "$cmd_id" --instance-id "$instance_id" \
      --query StandardErrorContent --output text >&2 || true
    return 1
  }
}

# ── 박스 안에서 돌 스크립트 조각 ──────────────────────────────────────────────
# 비밀번호는 넘기지 않는다. 박스가 자기 인스턴스 role 로 SSM 에서 읽는다(db-backup.sh 와 같은 방식).
# MYSQL_PWD 환경변수는 docker exec 안에서만 살아 원격 ps 에 노출되지 않는다.
remote_preamble() {
  cat <<PRE
set -euo pipefail
AWSCLI_IMAGE="$AWSCLI_IMAGE"
CONTAINER="$1"
SSM_PREFIX="$2"
REGION="$AWS_REGION"
WORK=/var/tmp/piki-migrate
mkdir -p "\$WORK"
aws_cli() { docker run --rm --network host -v "\$WORK:/work" "\$AWSCLI_IMAGE" "\$@"; }
ROOT_PW="\$(aws_cli ssm get-parameter --name "\$SSM_PREFIX/db-root-password" --with-decryption --region "\$REGION" --query Parameter.Value --output text)"
[ -n "\$ROOT_PW" ] && [ "\$ROOT_PW" != None ] || { echo "db-root-password 조회 실패 — 인스턴스 role·파라미터 확인" >&2; exit 1; }
PRE
}

# ── 단계 ─────────────────────────────────────────────────────────────────────

# 구 계정 앱 컨테이너(blue/green)를 정지해 신규 쓰기를 차단한다. 이 지점이 다운타임 시작.
# 무엇을 멈췄는지 파일로 남긴다 — 이후 어느 단계에서 실패해도 그 목록만 정확히 되살린다.
freeze_source() {
  log "freeze — 구 계정 앱 컨테이너 정지(쓰기 차단)"
  ssh "${SSHOPT[@]}" -i "$SRC_APP_KEY" "ubuntu@$SRC_APP_HOST" \
    'docker ps --format "{{.Names}}" | grep -E "^piki-core-(blue|green)$" || true' > "$FREEZE_STATE_FILE"
  if [ ! -s "$FREEZE_STATE_FILE" ]; then
    log "정지할 앱 컨테이너 없음 — freeze 생략"
    return 0
  fi
  log "정지 대상: $(tr '\n' ' ' < "$FREEZE_STATE_FILE")"
  # shellcheck disable=SC2087
  ssh "${SSHOPT[@]}" -i "$SRC_APP_KEY" "ubuntu@$SRC_APP_HOST" 'xargs -r docker stop' < "$FREEZE_STATE_FILE"
}

# 실패·중단 시 freeze 가 멈춘 그 컨테이너만 되살린다. DNS 전환까지 성공하면 워크플로가
# FREEZE_STATE_FILE 을 지워 이 경로를 무장해제한다(성공한 컷오버는 구 앱이 꺼진 채여야 한다).
rollback_freeze() {
  local rc=$?
  [ "$rc" -eq 0 ] && return 0
  [ -s "$FREEZE_STATE_FILE" ] || return 0
  log "실패(rc=$rc) — freeze 롤백: 구 앱 컨테이너 재기동"
  ssh "${SSHOPT[@]}" -i "$SRC_APP_KEY" "ubuntu@$SRC_APP_HOST" 'xargs -r docker start' < "$FREEZE_STATE_FILE" \
    && log "롤백 완료 — 구 계정 서비스 복구" \
    || log "롤백 실패 — 수동으로 구 앱 컨테이너를 기동해야 한다: $(tr '\n' ' ' < "$FREEZE_STATE_FILE")"
}

dump_to_relay() {
  log "덤프 — 구 계정 박스 안에서 실행 후 s3://$SRC_RELAY_BUCKET/$RELAY_KEY 로"
  { remote_preamble "$SRC_DB_CONTAINER" "$SRC_SSM_PREFIX"
    cat <<REMOTE
docker exec -e MYSQL_PWD="\$ROOT_PW" "\$CONTAINER" \\
  mysqldump -u root --single-transaction --routines --triggers --events --databases "$DB_NAME" \\
  | gzip > "\$WORK/dump.sql.gz"
SZ=\$(stat -c %s "\$WORK/dump.sql.gz")
[ "\$SZ" -gt 1024 ] || { echo "덤프가 비정상적으로 작다(\${SZ}B) — 손상 의심" >&2; exit 1; }
aws_cli s3 cp /work/dump.sql.gz "s3://$SRC_RELAY_BUCKET/$RELAY_KEY" --region "\$REGION" >/dev/null
rm -f "\$WORK/dump.sql.gz"
echo "DUMP_BYTES=\$SZ"
REMOTE
  } | remote_exec src | tee "$WORK/dump.out"
  grep -q '^DUMP_BYTES=' "$WORK/dump.out" || { log "덤프 결과를 확인하지 못했다"; return 1; }
  log "덤프 완료 — $(grep '^DUMP_BYTES=' "$WORK/dump.out" | tail -1)"
}

# 두 계정 버킷 사이엔 cross-account 정책이 없다. 러너가 구 자격으로 받아 신 자격(주변 환경)으로 올린다.
relay_dump() {
  log "릴레이 — 구 계정 버킷 → 러너 → 신 계정 버킷"
  env "AWS_ACCESS_KEY_ID=$SRC_AWS_KEY" "AWS_SECRET_ACCESS_KEY=$SRC_AWS_SECRET" --unset=AWS_SESSION_TOKEN \
    aws s3 cp "s3://$SRC_RELAY_BUCKET/$RELAY_KEY" "$WORK/dump.sql.gz" --region "$AWS_REGION" >/dev/null
  aws s3 cp "$WORK/dump.sql.gz" "s3://$DST_RELAY_BUCKET/$RELAY_KEY" --region "$AWS_REGION" >/dev/null
  rm -f "$WORK/dump.sql.gz"
  log "릴레이 완료"
}

restore_from_relay() {
  log "복원 — 신 계정 박스 안에서 실행"
  { remote_preamble "$DST_DB_CONTAINER" "$DST_SSM_PREFIX"
    cat <<REMOTE
aws_cli s3 cp "s3://$DST_RELAY_BUCKET/$RELAY_KEY" /work/dump.sql.gz --region "\$REGION" >/dev/null
gunzip < "\$WORK/dump.sql.gz" | docker exec -i -e MYSQL_PWD="\$ROOT_PW" "\$CONTAINER" mysql -u root
rm -f "\$WORK/dump.sql.gz"
echo RESTORE_OK
REMOTE
  } | remote_exec dst | tee "$WORK/restore.out"
  grep -q '^RESTORE_OK' "$WORK/restore.out" || { log "복원 결과를 확인하지 못했다"; return 1; }
  log "복원 완료"
}

# 저장된 전체 URL 에 박힌 버킷 호스트를 신 버킷으로. 대상 컬럼은 스키마 실측(전부 S3 public-base-url 로 조립):
#   users.profile_image · notifications.actor_image_url · item_snapshots.image_url
# public-base-url 이 계정무관 도메인(CloudFront 등)이면 OLD==NEW 라 통째로 no-op.
rewrite_urls() {
  [ "$OLD_IMAGE_BUCKET" = "$NEW_IMAGE_BUCKET" ] && { log "이미지 버킷 동일 — URL 재작성 생략(no-op)"; return 0; }
  log "이미지 URL 재작성 — $OLD_IMAGE_BUCKET → $NEW_IMAGE_BUCKET"
  { remote_preamble "$DST_DB_CONTAINER" "$DST_SSM_PREFIX"
    cat <<REMOTE
docker exec -i -e MYSQL_PWD="\$ROOT_PW" "\$CONTAINER" mysql -u root "$DB_NAME" <<'SQL'
UPDATE users          SET profile_image   = REPLACE(profile_image,   '$OLD_IMAGE_BUCKET', '$NEW_IMAGE_BUCKET') WHERE profile_image   LIKE '%$OLD_IMAGE_BUCKET%';
UPDATE notifications  SET actor_image_url = REPLACE(actor_image_url, '$OLD_IMAGE_BUCKET', '$NEW_IMAGE_BUCKET') WHERE actor_image_url LIKE '%$OLD_IMAGE_BUCKET%';
UPDATE item_snapshots SET image_url       = REPLACE(image_url,       '$OLD_IMAGE_BUCKET', '$NEW_IMAGE_BUCKET') WHERE image_url       LIKE '%$OLD_IMAGE_BUCKET%';
SQL
echo REWRITE_OK
REMOTE
  } | remote_exec dst | tee "$WORK/rewrite.out"
  grep -q '^REWRITE_OK' "$WORK/rewrite.out" || { log "URL 재작성 결과를 확인하지 못했다"; return 1; }
  log "URL 재작성 완료(3 컬럼)"
}

TABLES="users tournaments tournament_users items item_snapshots notifications"

# 한 번의 원격 호출로 전 테이블 COUNT 를 받는다(테이블마다 접속하면 호출이 12번으로 늘고
# SSM 모드에서는 그만큼 send-command 가 쌓인다).
counts_of() {
  local side="$1" container="$2" prefix="$3" sql=""
  local t first=1
  for t in $TABLES; do
    [ "$first" -eq 1 ] || sql="$sql UNION ALL"
    sql="$sql SELECT '$t' AS t, COUNT(*) AS c FROM \`$DB_NAME\`.\`$t\`"
    first=0
  done
  # SQL 을 -e 인자로 넘기지 않고 quoted heredoc(<<'SQL')으로 준다. 식별자 인용에 쓴 백틱이
  # 원격 셸의 큰따옴표 안에 들어가면 명령치환으로 해석돼 쿼리가 통째로 깨진다.
  { remote_preamble "$container" "$prefix"
    cat <<REMOTE
docker exec -i -e MYSQL_PWD="\$ROOT_PW" "\$CONTAINER" mysql -u root -N -B <<'SQL'
$sql;
SQL
REMOTE
  } | remote_exec "$side"
}

# 덤프 전(소스)·복원 후(대상) 주요 테이블 COUNT(*) 비교.
#
# rehearsal 에서는 불일치를 실패로 보지 않는다. freeze 가 없어 소스는 계속 쓰기를 받고, 소스
# COUNT 는 --single-transaction 스냅샷 이후의 라이브 값이다. 덤프 도중 정상적인 쓰기가 한 건만
# 들어와도 어긋나므로, 여기서 exit 1 하면 "정상 상황에서 실패하는 검증"이 된다.
# cutover 는 freeze 로 쓰기가 막힌 뒤라 불일치가 곧 이상 신호다 — 그때만 중단한다.
verify_counts() {
  log "행수 대조 — 주요 테이블"
  counts_of src "$SRC_DB_CONTAINER" "$SRC_SSM_PREFIX" | grep -E "^[a-z_]+[[:space:]]+[0-9]+$" > "$WORK/src.counts" || true
  counts_of dst "$DST_DB_CONTAINER" "$DST_SSM_PREFIX" | grep -E "^[a-z_]+[[:space:]]+[0-9]+$" > "$WORK/dst.counts" || true
  [ -s "$WORK/src.counts" ] && [ -s "$WORK/dst.counts" ] || { log "행수 조회 실패 — 대조 불가"; return 1; }

  local mismatch=0 t s d
  for t in $TABLES; do
    s=$(awk -v k="$t" '$1==k{print $2}' "$WORK/src.counts")
    d=$(awk -v k="$t" '$1==k{print $2}' "$WORK/dst.counts")
    log "  $t: src=${s:-?} dst=${d:-?}"
    [ "${s:-x}" = "${d:-y}" ] || mismatch=$((mismatch + 1))
  done

  if [ "$mismatch" -eq 0 ]; then
    log "행수 대조 통과"
    return 0
  fi
  if [ "$MODE" = rehearsal ]; then
    log "행수 불일치 $mismatch 건 — rehearsal 이라 경고로만 둔다(덤프 스냅샷 이후의 라이브 쓰기로 정상 발생)"
    return 0
  fi
  log "행수 불일치 $mismatch 건 — cutover 는 쓰기가 막힌 뒤라 이상 신호다. 중단"
  return 1
}

# ── 흐름 ─────────────────────────────────────────────────────────────────────
case "$MODE" in
  rehearsal)
    dump_to_relay; relay_dump; restore_from_relay; rewrite_urls; verify_counts
    log "rehearsal 완료 — 라이브 무영향"
    ;;
  cutover)
    rm -f "$FREEZE_STATE_FILE"
    # freeze 직전에 롤백 경계를 건다. 이 지점 이후의 어떤 실패도 구 앱을 되살리고 끝난다.
    trap rollback_freeze EXIT
    freeze_source
    dump_to_relay; relay_dump; restore_from_relay; rewrite_urls; verify_counts
    # 여기서 trap 을 풀지 않는다 — 남은 단계(S3 sync·스모크·DNS)는 워크플로가 돌리고,
    # 그쪽도 같은 FREEZE_STATE_FILE 로 migrate-unfreeze.sh 를 걸어 경계를 이어받는다.
    trap - EXIT
    log "cutover DB 파이프라인 완료 — 다음: S3 델타 sync·배포·스모크·DNS(워크플로가 처리)"
    log "롤백 경계 인계 — 남은 단계가 실패하면 워크플로가 $FREEZE_STATE_FILE 로 구 앱을 되살린다"
    ;;
  *)
    echo "[migrate-db] 알 수 없는 MODE=$MODE" >&2; exit 2 ;;
esac
