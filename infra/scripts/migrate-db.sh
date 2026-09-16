#!/usr/bin/env bash
# 계정 이관 DB 파이프라인 (#1092). GitHub 러너에서 돌며 구 계정 → 신 계정 앱 박스를 SSH 로 오간다.
#
#   rehearsal: 무중단(--single-transaction) 덤프 → 신 계정 복원 → 이미지 URL 재작성 → 행수 대조. 라이브 무영향.
#   cutover:   freeze(구 앱 정지)로 쓰기 차단 후 rehearsal 과 같은 파이프라인. 유일한 다운타임 구간.
#
# 왜 러너 스트리밍인가: 두 계정 버킷 간 cross-account S3 정책 없이도 덤프를 러너를 거쳐 옮긴다.
# 두 계정의 앱 박스 키페어는 이름이 같아도(team3-dev-SE-1) 키 재료가 달라 SRC/DST 키를 따로 받는다.
# 루트 비밀번호는 호출측(워크플로)이 SSM 에서 읽어 넘기고, ssh 로 MYSQL_PWD 환경변수로만 전달한다(로그 비노출).
#
# 실측 확정이 필요한 입력(dev 카나리에서 확인): DB_NAME(스키마명) · 각 박스 루트 비번 · 구 계정 박스 호스트/키.
set -euo pipefail

MODE="${MODE:?rehearsal|cutover 를 지정}"
DB_NAME="${DB_NAME:?이관할 스키마명 (예: piki)}"
DB_CONTAINER="${DB_CONTAINER:?MySQL 컨테이너명 (piki-mysql|piki-prod-mysql)}"
SRC_HOST="${SRC_HOST:?구 계정 앱 박스 호스트}"
DST_HOST="${DST_HOST:?신 계정 앱 박스 호스트}"
SRC_KEY="${SRC_KEY:?구 계정 SSH 키파일 경로}"
DST_KEY="${DST_KEY:?신 계정 SSH 키파일 경로}"
SRC_ROOT_PW="${SRC_ROOT_PW:?구 계정 DB root 비번}"
DST_ROOT_PW="${DST_ROOT_PW:?신 계정 DB root 비번}"
OLD_IMAGE_BUCKET="${OLD_IMAGE_BUCKET:?구 이미지 버킷명}"
NEW_IMAGE_BUCKET="${NEW_IMAGE_BUCKET:?신 이미지 버킷명}"

SSHOPT=(-o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=/dev/null -o ConnectTimeout=15)
DUMP=/tmp/migrate-dump.sql.gz

log() { echo "[migrate-db] $(date -u +%H:%M:%S) $*"; }

# 구 계정 앱 컨테이너(blue/green)를 정지해 신규 쓰기를 차단한다. 이 지점이 다운타임 시작.
freeze_source() {
  log "freeze — 구 계정 앱 컨테이너 정지(쓰기 차단)"
  ssh "${SSHOPT[@]}" -i "$SRC_KEY" "ubuntu@$SRC_HOST" \
    'docker ps --format "{{.Names}}" | grep -E "^piki-core-(blue|green)$" | xargs -r docker stop'
}

dump() {
  log "덤프 시작 — $SRC_HOST:$DB_CONTAINER/$DB_NAME (single-transaction, 무락)"
  ssh "${SSHOPT[@]}" -i "$SRC_KEY" "ubuntu@$SRC_HOST" \
    "docker exec -e MYSQL_PWD='$SRC_ROOT_PW' $DB_CONTAINER \
       mysqldump -u root --single-transaction --routines --triggers --events --databases $DB_NAME" \
    | gzip > "$DUMP"
  local sz
  sz=$(stat -c%s "$DUMP" 2>/dev/null || stat -f%z "$DUMP")
  [ "$sz" -gt 1000 ] || { log "덤프가 비정상적으로 작다(${sz}B) — 손상 의심, 중단"; exit 1; }
  log "덤프 완료 — ${sz}B"
}

restore() {
  log "복원 시작 — $DST_HOST:$DB_CONTAINER"
  gunzip < "$DUMP" | ssh "${SSHOPT[@]}" -i "$DST_KEY" "ubuntu@$DST_HOST" \
    "docker exec -i -e MYSQL_PWD='$DST_ROOT_PW' $DB_CONTAINER mysql -u root"
  log "복원 완료"
}

# 저장된 전체 URL 에 박힌 버킷 호스트를 신 버킷으로. 대상 컬럼은 스키마 실측(전부 S3 public-base-url 로 조립):
#   users.profile_image · notifications.actor_image_url · item_snapshots.image_url
# public-base-url 이 계정무관 도메인(CloudFront 등)이면 OLD==NEW 라 통째로 no-op.
rewrite_urls() {
  [ "$OLD_IMAGE_BUCKET" = "$NEW_IMAGE_BUCKET" ] && { log "이미지 버킷 동일 — URL 재작성 생략(no-op)"; return 0; }
  log "이미지 URL 재작성 — $OLD_IMAGE_BUCKET → $NEW_IMAGE_BUCKET"
  # heredoc 확장은 의도적으로 클라이언트(러너) 측 — OLD/NEW 버킷명은 러너 env 라 여기서 SQL 에 박혀야 한다.
  # 원격 확장이면 박스에 그 변수가 없어 빈 값이 된다. 따라서 <<SQL 은 unquoted 가 맞다.
  # shellcheck disable=SC2087
  ssh "${SSHOPT[@]}" -i "$DST_KEY" "ubuntu@$DST_HOST" \
    "docker exec -i -e MYSQL_PWD='$DST_ROOT_PW' $DB_CONTAINER mysql -u root $DB_NAME" <<SQL
UPDATE users          SET profile_image   = REPLACE(profile_image,   '$OLD_IMAGE_BUCKET', '$NEW_IMAGE_BUCKET') WHERE profile_image   LIKE '%$OLD_IMAGE_BUCKET%';
UPDATE notifications  SET actor_image_url = REPLACE(actor_image_url, '$OLD_IMAGE_BUCKET', '$NEW_IMAGE_BUCKET') WHERE actor_image_url LIKE '%$OLD_IMAGE_BUCKET%';
UPDATE item_snapshots SET image_url       = REPLACE(image_url,       '$OLD_IMAGE_BUCKET', '$NEW_IMAGE_BUCKET') WHERE image_url       LIKE '%$OLD_IMAGE_BUCKET%';
SQL
  log "URL 재작성 완료(3 컬럼)"
}

# 덤프 전(소스)·복원 후(대상) 주요 테이블 COUNT(*) 비교. 하나라도 어긋나면 중단.
verify_counts() {
  log "행수 대조 — 주요 테이블"
  local t s d
  for t in users tournaments tournament_users items item_snapshots notifications; do
    s=$(ssh "${SSHOPT[@]}" -i "$SRC_KEY" "ubuntu@$SRC_HOST" "docker exec -e MYSQL_PWD='$SRC_ROOT_PW' $DB_CONTAINER mysql -u root -N -e 'SELECT COUNT(*) FROM \`$DB_NAME\`.\`$t\`'")
    d=$(ssh "${SSHOPT[@]}" -i "$DST_KEY" "ubuntu@$DST_HOST" "docker exec -e MYSQL_PWD='$DST_ROOT_PW' $DB_CONTAINER mysql -u root -N -e 'SELECT COUNT(*) FROM \`$DB_NAME\`.\`$t\`'")
    log "  $t: src=$s dst=$d"
    [ "$s" = "$d" ] || { log "행수 불일치 — $t (src=$s dst=$d) — 중단"; exit 1; }
  done
  log "행수 대조 통과"
}

case "$MODE" in
  rehearsal)
    dump; restore; rewrite_urls; verify_counts
    log "rehearsal 완료 — 라이브 무영향"
    ;;
  cutover)
    freeze_source
    dump; restore; rewrite_urls; verify_counts
    log "cutover DB 파이프라인 완료 — 다음: S3 델타 sync·배포·스모크·DNS(워크플로가 처리)"
    ;;
  *)
    echo "[migrate-db] 알 수 없는 MODE=$MODE" >&2; exit 2 ;;
esac
