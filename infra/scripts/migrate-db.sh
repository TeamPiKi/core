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
# root 비밀번호가 담긴 SSM 파라미터 이름. env 마다 다르다 —
#   prod: 별도 DB 박스를 provision-db.sh 가 띄우며 db-root-password 를 따로 둔다.
#   dev : provision-runtime.sh 가 MYSQL_ROOT_PASSWORD="${DB_PASSWORD}" 로 띄워 db-password 가 곧 root 비번이고,
#         db-root-password 라는 파라미터는 애초에 없다(실측 — 이 값을 고정하면 dev 에서 ParameterNotFound).
ROOT_PW_PARAM="${ROOT_PW_PARAM:-db-root-password}"
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

# 구 계정 자격 프로필(migrate-src-creds 가 만든다). 러너가 구 계정에 ssm send-command·s3 를 칠 때만 쓴다.
# 신 계정은 러너의 주변 환경(호출측이 OIDC 로 맡은 역할)을 그대로 쓴다.
SRC_AWS_PROFILE="${SRC_AWS_PROFILE:?구 계정 프로필}"

# freeze 가 실제로 멈춘 컨테이너 목록. 워크플로의 후속 단계(S3 sync·스모크·DNS)까지 같은 롤백
# 경계를 공유하려고 파일로 남긴다 — migrate-unfreeze.sh 가 이 파일을 읽어 되살린다.
FREEZE_STATE_FILE="${FREEZE_STATE_FILE:-/tmp/piki-migrate-frozen}"

# Redis 는 두 환경 모두 DB 와 같은 박스에 있어(prod DB 박스·dev 앱 박스) DB 와 같은 경로로 옮긴다.
REDIS_CONTAINER="${REDIS_CONTAINER:?Redis 컨테이너명}"

# ConnectTimeout 은 주지 않는다. 호스트는 SSM 별칭이라 ssm-ssh 가 쓴 30초가 맞고, 명령줄 값이 그걸 덮는다.
SSHOPT=(-o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=/dev/null)
AWSCLI_IMAGE="public.ecr.aws/aws-cli/aws-cli:2.35.21" # db-backup.sh 와 같은 핀. 박스에 aws CLI 가 없어도 된다.
RELAY_KEY="migrate/${DB_NAME}-$(date -u +%Y%m%dT%H%M%SZ).sql.gz"
REDIS_RELAY_KEY="migrate/redis-$(date -u +%Y%m%dT%H%M%SZ).tgz"
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
    _exec_ssm "$SRC_DB_TAG" "$SRC_AWS_PROFILE" "$body"
  else
    [ "$DST_EXEC" = ssh ] && { _exec_ssh "$DST_DB_HOST" "$DST_DB_KEY" "$body"; return; }
    # 빈 프로필 = 러너의 주변 환경을 쓴다(OIDC 로 맡은 신 계정 역할).
    _exec_ssm "$DST_DB_TAG" "" "$body"
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
  local tag="$1" profile="$2" body="$3" cmd_id instance_id status
  # 프로필을 호출마다 붙인다 — AWS_PROFILE 로 export 하면 같은 셸의 반대 계정 호출까지 물든다.
  local -a A=(env "AWS_DEFAULT_REGION=$AWS_REGION" aws)
  [ -z "$profile" ] || A+=(--profile "$profile")
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
ROOT_PW_PARAM="$ROOT_PW_PARAM"
ROOT_PW="\$(aws_cli ssm get-parameter --name "\$SSM_PREFIX/\$ROOT_PW_PARAM" --with-decryption --region "\$REGION" --query Parameter.Value --output text)"
[ -n "\$ROOT_PW" ] && [ "\$ROOT_PW" != None ] || { echo "\$ROOT_PW_PARAM 조회 실패 — 인스턴스 role·파라미터 확인" >&2; exit 1; }
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

# ── 직전송 경로 (양쪽 다 ssh 일 때) ──────────────────────────────────────────
# S3 릴레이를 타지 않는다. 릴레이는 keyless 박스(prod DB)를 SSM 으로만 조종할 수 있어서 둔 우회로인데,
# dev 는 러너가 앱 박스에 SSH 로 직접 닿는다. 게다가 dev 용 릴레이 버킷은 존재하지 않는다 —
# piki-db-backup-* 는 prod DB 박스 백업용으로 만든 것이라 dev 덤프를 올릴 자리가 아니다(실측).
# 그래서 덤프를 러너 표준입출력으로 바로 흘려보낸다.
dump_direct() {
  log "덤프(직전송) — 구 계정 박스에서 러너로"
  { remote_preamble "$SRC_DB_CONTAINER" "$SRC_SSM_PREFIX"
    cat <<REMOTE
docker exec -e MYSQL_PWD="\$ROOT_PW" "\$CONTAINER" \\
  mysqldump -u root --single-transaction --routines --triggers --events --databases "$DB_NAME" | gzip
REMOTE
  # _exec_ssh 를 쓰지 않는다 — 그쪽은 본문을 세 번째 인자로 받는데(set -u 라 빠지면 unbound variable),
  # 여기서는 본문을 파이프로 흘리고 원격 stdout(gzip 바이너리)을 그대로 받아야 한다.
  } | ssh "${SSHOPT[@]}" -i "$SRC_DB_KEY" "ubuntu@$SRC_DB_HOST" 'bash -s' > "$WORK/dump.sql.gz"
  local sz; sz=$(stat -c %s "$WORK/dump.sql.gz" 2>/dev/null || stat -f %z "$WORK/dump.sql.gz")
  [ "$sz" -gt 1024 ] || { log "덤프가 비정상적으로 작다(${sz}B) — 손상 의심"; return 1; }
  log "덤프 완료 — ${sz}B"
}

restore_direct() {
  log "복원(직전송) — 러너에서 신 계정 박스로"
  # 덤프를 먼저 박스에 올린 뒤 스크립트를 실행한다. 스크립트와 덤프를 한 스트림으로 이으면
  # `bash -s` 가 자기 입력을 블록 단위로 읽어 뒤쪽 덤프까지 삼켜버려, 원격의 `cat > dump.gz` 가
  # 받을 데이터가 남지 않는다(조용히 빈 파일이 되어 복원이 깨진다).
  scp "${SSHOPT[@]}" -i "$DST_DB_KEY" "$WORK/dump.sql.gz" "ubuntu@$DST_DB_HOST:/var/tmp/piki-migrate-dump.sql.gz" >/dev/null
  { remote_preamble "$DST_DB_CONTAINER" "$DST_SSM_PREFIX"
    cat <<'REMOTE'
gunzip < /var/tmp/piki-migrate-dump.sql.gz | docker exec -i -e MYSQL_PWD="$ROOT_PW" "$CONTAINER" mysql -u root
rm -f /var/tmp/piki-migrate-dump.sql.gz
echo RESTORE_OK
REMOTE
  } | ssh "${SSHOPT[@]}" -i "$DST_DB_KEY" "ubuntu@$DST_DB_HOST" 'bash -s' | tee "$WORK/restore.out"
  grep -q '^RESTORE_OK' "$WORK/restore.out" || { log "복원 결과를 확인하지 못했다"; return 1; }
  rm -f "$WORK/dump.sql.gz"
  log "복원 완료"
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
  aws --profile "$SRC_AWS_PROFILE" s3 cp "s3://$SRC_RELAY_BUCKET/$RELAY_KEY" "$WORK/dump.sql.gz" --region "$AWS_REGION" >/dev/null
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

# 한 번의 원격 호출로 전 테이블 COUNT 를 받는다(테이블마다 접속하면 SSM 모드에서 send-command 가 쌓인다).
# 테이블 목록은 서버에서 읽는다. 코드에 박아 두면 이후 추가된 테이블이 대조 밖으로 빠진다.
counts_of() {
  local side="$1" container="$2" prefix="$3" sql
  sql="SET SESSION group_concat_max_len = 1000000;
SELECT GROUP_CONCAT(CONCAT('SELECT ''', table_name, ''', COUNT(*) FROM \`', table_schema, '\`.\`', table_name, '\`') SEPARATOR ' UNION ALL ')
  INTO @q FROM information_schema.tables WHERE table_schema = '$DB_NAME' AND table_type = 'BASE TABLE';
PREPARE s FROM @q; EXECUTE s; DEALLOCATE PREPARE s"
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

# 덤프 전(소스)·복원 후(대상) 전체 테이블 COUNT(*) 비교.
#
# rehearsal 은 freeze 가 없어 소스가 계속 쓰기를 받으므로 불일치를 경고로만 둔다.
# cutover 는 소스 쓰기가 막혔지만 신 계정 앱은 이미 떠 있어 대상에 행이 늘 수 있다. 대상이 적을 때만 유실로 보고 중단한다.
verify_counts() {
  log "행수 대조 — 전체 테이블"
  counts_of src "$SRC_DB_CONTAINER" "$SRC_SSM_PREFIX" | grep -E "^[a-z0-9_]+[[:space:]]+[0-9]+$" > "$WORK/src.counts" || true
  counts_of dst "$DST_DB_CONTAINER" "$DST_SSM_PREFIX" | grep -E "^[a-z0-9_]+[[:space:]]+[0-9]+$" > "$WORK/dst.counts" || true
  [ -s "$WORK/src.counts" ] && [ -s "$WORK/dst.counts" ] || { log "행수 조회 실패 — 대조 불가"; return 1; }

  local missing=0 extra=0 t s d
  while read -r t s; do
    d=$(awk -v k="$t" '$1==k{print $2}' "$WORK/dst.counts")
    log "  $t: src=$s dst=${d:-?}"
    if [ -z "$d" ] || [ "$d" -lt "$s" ]; then missing=$((missing + 1))
    elif [ "$d" -gt "$s" ]; then extra=$((extra + 1)); fi
  done < "$WORK/src.counts"

  [ "$extra" -eq 0 ] || log "대상이 더 많은 테이블 $extra 건 — 복원 뒤 신 계정 앱의 쓰기로 정상 발생"
  if [ "$missing" -eq 0 ]; then
    log "행수 대조 통과"
    return 0
  fi
  if [ "$MODE" = rehearsal ]; then
    log "대상이 더 적은 테이블 $missing 건 — rehearsal 이라 경고로만 둔다(덤프 스냅샷 이후의 라이브 쓰기로 정상 발생)"
    return 0
  fi
  log "대상이 더 적은 테이블 $missing 건 — 쓰기가 막힌 뒤라 유실 신호다. 중단"
  return 1
}

# ── Redis ────────────────────────────────────────────────────────────────────
# 구 Redis 는 멈추지 않는다. SAVE 로 디스크에 내린 뒤 데이터 폴더를 통째로 묶어, 롤백할 때 되살릴 것이 없다.
# 폴더 전체라 AOF 를 켜도 appendonlydir 이 함께 간다. 볼륨은 이름이 아니라 컨테이너로 잡는다(이름이 틀리면 빈 볼륨이 새로 생겨 조용히 성공한다).
redis_vars() {
  printf 'set -euo pipefail\nREDIS_CONTAINER=%q\nAWSCLI_IMAGE=%q\nREGION=%q\n' \
    "$REDIS_CONTAINER" "$AWSCLI_IMAGE" "$AWS_REGION"
  cat <<'REMOTE'
WORK=/var/tmp/piki-migrate
mkdir -p "$WORK"
aws_cli() { docker run --rm --network host -v "$WORK:/work" "$AWSCLI_IMAGE" "$@"; }
IMG=$(docker inspect -f '{{.Config.Image}}' "$REDIS_CONTAINER")
rcli() { docker exec "$REDIS_CONTAINER" sh -c '[ -z "${REDIS_PASSWORD:-}" ] || export REDISCLI_AUTH="$REDIS_PASSWORD"; redis-cli "$@"' rcli "$@"; }
REMOTE
}

# $1 이 있으면 묶은 파일을 그 S3 위치로 올리고, 없으면 표준출력으로 흘린다(직전송).
# shellcheck disable=SC2016  # $REGION·$WORK 는 박스에서 펼친다
redis_pack() {
  redis_vars
  cat <<'REMOTE'
[ "$(rcli SAVE)" = OK ] || { echo "Redis SAVE 실패" >&2; exit 1; }
echo "REDIS_KEYS=$(rcli DBSIZE)" >&2
docker run --rm --entrypoint tar --volumes-from "$REDIS_CONTAINER:ro" -v "$WORK:/out" "$IMG" czf /out/redis.tgz -C /data .
REMOTE
  if [ -n "${1:-}" ]; then
    printf 'aws_cli s3 cp /work/redis.tgz %q --region "$REGION" >/dev/null\n' "$1"
  else
    echo 'cat "$WORK/redis.tgz"'
  fi
  echo 'rm -f "$WORK/redis.tgz"'
}

# $1 이 있으면 그 S3 위치에서 먼저 받는다. 없으면 러너가 미리 올려 둔 파일을 쓴다(직전송).
# shellcheck disable=SC2016  # $REGION 은 박스에서 펼친다
redis_unpack() {
  redis_vars
  [ -z "${1:-}" ] || printf 'aws_cli s3 cp %q /work/redis.tgz --region "$REGION" >/dev/null\n' "$1"
  cat <<'REMOTE'
docker stop "$REDIS_CONTAINER" >/dev/null
trap 'docker start "$REDIS_CONTAINER" >/dev/null' EXIT
docker run --rm --entrypoint sh --volumes-from "$REDIS_CONTAINER" -v "$WORK:/in" "$IMG" \
  -c 'find /data -mindepth 1 -delete && tar xzf /in/redis.tgz -C /data'
rm -f "$WORK/redis.tgz"
docker start "$REDIS_CONTAINER" >/dev/null
trap - EXIT
for _ in 1 2 3 4 5 6 7 8 9 10; do
  N=$(rcli DBSIZE 2>/dev/null) && [ -n "$N" ] && { echo "REDIS_RESTORED=$N"; exit 0; }
  sleep 1
done
echo "복원 뒤 Redis 응답 없음" >&2; exit 1
REMOTE
}

# Redis 가 비어도 서비스는 돈다(사용자 재로그인). 이 단계 실패로 컷오버를 되돌리지 않고 경고로 남긴다.
redis_transfer() {
  log "Redis 이전 — SAVE 뒤 데이터 폴더 복사"
  if [ "$SRC_EXEC" = ssh ] && [ "$DST_EXEC" = ssh ]; then
    redis_pack | ssh "${SSHOPT[@]}" -i "$SRC_DB_KEY" "ubuntu@$SRC_DB_HOST" 'bash -s' > "$WORK/redis.tgz" || return 1
    ssh "${SSHOPT[@]}" -i "$DST_DB_KEY" "ubuntu@$DST_DB_HOST" 'mkdir -p /var/tmp/piki-migrate' || return 1
    scp "${SSHOPT[@]}" -i "$DST_DB_KEY" "$WORK/redis.tgz" "ubuntu@$DST_DB_HOST:/var/tmp/piki-migrate/redis.tgz" >/dev/null || return 1
    redis_unpack | ssh "${SSHOPT[@]}" -i "$DST_DB_KEY" "ubuntu@$DST_DB_HOST" 'bash -s' | tee "$WORK/redis.out" || return 1
  else
    redis_pack "s3://$SRC_RELAY_BUCKET/$REDIS_RELAY_KEY" | remote_exec src || return 1
    aws --profile "$SRC_AWS_PROFILE" s3 cp "s3://$SRC_RELAY_BUCKET/$REDIS_RELAY_KEY" "$WORK/redis.tgz" --region "$AWS_REGION" >/dev/null || return 1
    aws s3 cp "$WORK/redis.tgz" "s3://$DST_RELAY_BUCKET/$REDIS_RELAY_KEY" --region "$AWS_REGION" >/dev/null || return 1
    redis_unpack "s3://$DST_RELAY_BUCKET/$REDIS_RELAY_KEY" | remote_exec dst | tee "$WORK/redis.out" || return 1
  fi
  rm -f "$WORK/redis.tgz"
  grep -q '^REDIS_RESTORED=' "$WORK/redis.out" || return 1
  log "Redis 이전 완료 — 키 $(sed -n 's/^REDIS_RESTORED=//p' "$WORK/redis.out" | tail -1)개"
}

# ── 흐름 ─────────────────────────────────────────────────────────────────────
# 전송 경로 선택 — 양쪽 다 ssh 면 러너가 직접 닿으므로 S3 릴레이가 불필요하고, dev 는 릴레이 버킷
# 자체가 없어 직전송만 성립한다. 한쪽이라도 ssm(keyless prod DB 박스)이면 릴레이를 탄다.
transfer() {
  if [ "$SRC_EXEC" = ssh ] && [ "$DST_EXEC" = ssh ]; then
    dump_direct; restore_direct
  else
    dump_to_relay; relay_dump; restore_from_relay
  fi
}

case "$MODE" in
  rehearsal)
    transfer; rewrite_urls; verify_counts
    redis_transfer || echo "::warning::Redis 이전 리허설 실패 — 컷오버 전에 원인 확인"
    log "rehearsal 완료 — 라이브 무영향"
    ;;
  cutover)
    rm -f "$FREEZE_STATE_FILE"
    # freeze 직전에 롤백 경계를 건다. 이 지점 이후의 어떤 실패도 구 앱을 되살리고 끝난다.
    trap rollback_freeze EXIT
    freeze_source
    transfer; rewrite_urls; verify_counts
    redis_transfer || echo "::warning::Redis 이전 실패 — 서비스는 돌지만 사용자가 다시 로그인해야 한다"
    # 여기서 trap 을 풀지 않는다 — 남은 단계(S3 sync·스모크·DNS)는 워크플로가 돌리고,
    # 그쪽도 같은 FREEZE_STATE_FILE 로 migrate-unfreeze.sh 를 걸어 경계를 이어받는다.
    trap - EXIT
    log "cutover DB 파이프라인 완료 — 다음: S3 델타 sync·스모크·전달(워크플로가 처리)"
    log "롤백 경계 인계 — 남은 단계가 실패하면 워크플로가 $FREEZE_STATE_FILE 로 구 앱을 되살린다"
    ;;
  *)
    echo "[migrate-db] 알 수 없는 MODE=$MODE" >&2; exit 2 ;;
esac
