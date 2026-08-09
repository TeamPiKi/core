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
# node_exporter textfile collector 가 읽는 디렉터리. alloy 가 이 경로를 컨테이너로 마운트해
# 여기 놓인 .prom 을 그대로 Grafana 로 보낸다(#905).
TEXTFILE_DIR="/var/lib/node_exporter/textfile"
METRIC_FILE="${TEXTFILE_DIR}/piki_db_backup.prom"

log() { echo "[db-backup] $(date -u +%Y-%m-%dT%H:%M:%SZ) $*"; }

ssm_param() {
  docker run --rm --network host "$AWSCLI_IMAGE" ssm get-parameter \
    --name "${SSM_PREFIX}/$1" --with-decryption \
    --region "$REGION" --query Parameter.Value --output text
}

# ── 관측·알림 ───────────────────────────────────────────────────────────────
# 두 층을 둔다. Discord 는 실패를 즉시 알리고, 지표는 "마지막 성공이 언제였나"를 남겨
# 스크립트가 아예 실행되지 않은 침묵까지 잡는다 — 실패 알림만으로는 그 경우를 못 본다.

# 지표는 임시 파일에 쓰고 mv 로 교체한다. node_exporter 가 읽는 중에 부분 기록을 보면
# 깨진 값이 그대로 전송되므로, 교체를 원자적으로 해야 한다.
write_metrics() {
  local status="$1" size="$2" now prev_success tmp
  now="$(date -u +%s)"
  # 마지막 성공 시각은 실패해도 보존한다. 이 값이 알림의 기준이라 덮어쓰면 안 된다.
  prev_success="$(sed -n 's/^piki_db_backup_last_success_timestamp_seconds \([0-9]*\)$/\1/p' "$METRIC_FILE" 2>/dev/null || true)"
  [ -n "$prev_success" ] || prev_success=0
  [ "$status" = "1" ] && prev_success="$now"

  mkdir -p "$TEXTFILE_DIR" 2>/dev/null || true
  tmp="$(mktemp "${METRIC_FILE}.XXXXXX")" || { log "지표 임시파일 생성 실패 — 기록 생략"; return 0; }
  {
    echo "# HELP piki_db_backup_last_run_timestamp_seconds 백업 스크립트가 마지막으로 실행된 시각"
    echo "# TYPE piki_db_backup_last_run_timestamp_seconds gauge"
    echo "piki_db_backup_last_run_timestamp_seconds ${now}"
    echo "# HELP piki_db_backup_last_success_timestamp_seconds 백업이 마지막으로 성공한 시각"
    echo "# TYPE piki_db_backup_last_success_timestamp_seconds gauge"
    echo "piki_db_backup_last_success_timestamp_seconds ${prev_success}"
    echo "# HELP piki_db_backup_last_status 마지막 실행 결과 (1=성공, 0=실패)"
    echo "# TYPE piki_db_backup_last_status gauge"
    echo "piki_db_backup_last_status ${status}"
    echo "# HELP piki_db_backup_last_size_bytes 마지막 성공한 백업의 압축 크기"
    echo "# TYPE piki_db_backup_last_size_bytes gauge"
    echo "piki_db_backup_last_size_bytes ${size}"
  } > "$tmp"
  if chmod 644 "$tmp" && mv -f "$tmp" "$METRIC_FILE"; then
    return 0
  fi
  rm -f "$tmp"
  log "지표 기록 실패 — 백업 자체는 계속한다"
}

# 알림 실패가 백업을 죽이면 안 된다. 여기서 나는 오류는 전부 로그로만 남기고 넘어간다.
#
# 성공도 함께 알린다. "매일 오던 게 안 왔다"를 사람이 알아채는 것이 cron 자체가 죽은 침묵을
# 잡는 가장 단순한 장치다(지표 기반 룰이 붙기 전까지는 사실상 유일하다).
notify_discord() {
  local title="$1" detail="$2" url payload safe_detail
  # 배포 알림(DISCORD_WEBHOOK_URL)과 다른 채널을 쓴다. 운영 알림이 배포 로그에 섞이면
  # 정작 봐야 할 때 묻히므로, 파라미터 이름부터 갈라 둔다.
  url="$(ssm_param discord-alert-webhook-url 2>/dev/null)" || { log "alert webhook URL 조회 실패 — 알림 생략"; return 0; }
  [ -n "$url" ] && [ "$url" != "None" ] || { log "webhook URL 이 비어 있다 — 알림 생략"; return 0; }

  # JSON 을 손으로 만들기 때문에 따옴표·역슬래시·개행을 미리 걷어낸다. 알림 문구가 조금
  # 뭉개져도 신호가 전달되는 게 우선이고, 깨진 JSON 은 아예 전달을 못 한다.
  # \042 = 큰따옴표, \134 = 역슬래시. 8진수로 쓰는 이유는 셸 인용 안에서 백슬래시를
  # 이스케이프하면 의도가 흐려지고 정적 분석도 오해하기 때문이다.
  safe_detail="$(printf '%s' "$detail" | tr -d '\042\134' | tr '\n' ' ' | cut -c1-400)"
  payload="$(printf '{"content":"**%s**\\n시각: %s\\n호스트: %s\\n%s"}' \
    "$title" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$(hostname)" "$safe_detail")"

  # URL 은 인자로 넘기지 않고 stdin 으로 준다 — ps 출력에 노출되지 않게.
  if ! printf '%s' "$url" | xargs -r curl -s --max-time 10 -o /dev/null \
      -X POST -H "Content-Type: application/json" -d "$payload"; then
    log "Discord 알림 전송 실패 (백업 결과에는 영향 없음)"
  fi
}

# 실패 경로를 한곳으로 모은다. 지표·알림·로그를 빠짐없이 남기고 비정상 종료한다.
fail() {
  local stage="$1" detail="$2"
  log "실패(${stage}): ${detail}"
  write_metrics 0 0
  notify_discord "piki prod DB 백업 실패" "단계: ${stage}
상세: ${detail}"
  exit 1
}

DB_NAME="$(ssm_param db-name)" || fail "SSM 조회" "db-name 을 읽지 못했다 (IAM·파라미터 존재 확인)"
# 덤프는 root 로 뜬다(전 스키마 접근). root 비밀번호는 앱 계정(db-password)과 분리돼 있으므로
# 여기서 db-root-password 를 읽는다 — 앱 자격증명이 새도 백업 경로의 권한은 함께 넘어가지 않는다.
DB_ROOT_PASSWORD="$(ssm_param db-root-password)" || fail "SSM 조회" "db-root-password 를 읽지 못했다"
BUCKET="$(ssm_param db-backup-bucket)" || fail "SSM 조회" "db-backup-bucket 을 읽지 못했다"

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
  DUMP_ERR="$(tail -3 "${WORK_DIR}/dump.err" 2>/dev/null || true)"
  rm -f "${WORK_DIR}/${FILE}"
  fail "덤프" "${DUMP_ERR:-원인 미상}"
fi

SIZE="$(stat -c %s "${WORK_DIR}/${FILE}")"
# 빈 파일·헤더만 있는 산출물이 "성공"으로 올라가 복구 시점에야 발각되는 걸 막는다.
# 정상 덤프는 gzip 후에도 최소 수 KB 다.
if [ "$SIZE" -lt 1024 ]; then
  rm -f "${WORK_DIR}/${FILE}"
  fail "크기 검사" "덤프 산출물이 비정상적으로 작다 (${SIZE} bytes)"
fi

log "업로드: s3://${BUCKET}/${FILE} (${SIZE} bytes)"
if ! docker run --rm --network host -v "${WORK_DIR}:/backup" "$AWSCLI_IMAGE" \
  s3 cp "/backup/${FILE}" "s3://${BUCKET}/${FILE}" --region "$REGION" > /dev/null; then
  fail "S3 업로드" "업로드에 실패했다. 로컬 파일은 ${WORK_DIR}/${FILE} 에 남겨 뒀다"
fi

# 업로드가 끝난 로컬 사본은 지운다. 루트 볼륨이 10GB 라 쌓이면 곧 찬다.
# (실패 시엔 위에서 남겨 두고 종료해 수동 회수 여지를 준다.)
rm -f "${WORK_DIR}/${FILE}" "${WORK_DIR}/dump.err"
write_metrics 1 "$SIZE"
log "완료"
notify_discord "piki prod DB 백업 성공" "파일: ${FILE} (${SIZE} bytes)"
