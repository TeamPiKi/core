#!/usr/bin/env bash
# prod 마이그레이션 프리플라이트 — promote 승인 앞에서 "이 배포가 마이그레이션에서 죽을 조건"을 미리 읽는다 (#1103).
#
# 왜 있나: Flyway 는 앱 부팅 중에 돈다. 거기서 예외가 나면 새 슬롯이 뜨지 못하고 헬스체크 5분을 다
# 기다린 뒤에야 롤백된다 — dev 가 #1044 배포에서 정확히 그렇게 죽었다(클론 202 가 참여자 2명, 당시
# 백필의 "클론당 참여자=1" 가드 위반). 그 조건을 읽기 전용 SELECT 로 먼저 세어 본다.
#
# 검사는 두 층이다.
#   상설 — Flyway 실패 잔재(success=0). 마이그레이션 종류와 무관하게 항상 돈다. 한 번 실패한 행이
#          남으면 이후 모든 배포가 그 지점에서 멈추므로(dev 가 #1044 뒤 repair 전까지 그랬다),
#          승격 전에 알아야 할 값어치가 영구히 남는다.
#   한시 — 클론 평탄화 백필(V20260906205407)의 가드 2종. 그 백필이 prod 에 적용되면 자동으로 꺼진다.
#          #1027 Phase 4 로 클론 잔재를 걷을 때 이 절을 통째로 지운다.
#
# 읽기만 한다. UPDATE·DELETE·DDL 이 한 줄도 없어 언제 몇 번 돌려도 운영 데이터에 영향이 없다.
#
# 실행: 앱 박스에서 돈다 (DB 가 프라이빗 서브넷이라 러너가 직접 못 닿는다).
#   scp infra/scripts/preflight-migration.sh ubuntu@<APP_BOX>:/tmp/
#   ssh ubuntu@<APP_BOX> 'bash /tmp/preflight-migration.sh prod'
#
# 출력은 GitHub Step Summary 에 그대로 붙일 마크다운이다(stdout). 진단·에러는 stderr 로 분리해,
# 캡처한 stdout 이 항상 렌더 가능한 마크다운만 담게 한다.
#
# 종료 코드: 0 = 통과, 1 = 가드 위반(배포 중단), 2 = 프리플라이트 자체 실패(조회 불가).
set -euo pipefail

ENVIRONMENT="${1:-prod}"
# 이미지 핀은 provision-runtime.sh·deploy.yml 의 SSM pull 과 같은 버전을 쓴다 (박스에 aws cli 가 없다).
AWSCLI_IMAGE="public.ecr.aws/aws-cli/aws-cli:2.35.21"
# 앱 박스에는 mysql 클라이언트도 없다(신 계정 박스 실측, #1163). DB 박스가 띄우는 서버와 같은 이미지(provision-runtime.sh)로 클라이언트를 돌린다.
MYSQL_IMAGE="mysql:8.4"
REGION="ap-northeast-2"
SSM_PREFIX="/piki-core/${ENVIRONMENT}"
# 한시 절이 지키는 백필. 이 버전이 적용돼 있으면 클론 가드를 건너뛴다(상설 절은 그대로 돈다).
BACKFILL_VERSION="20260906205407"
# 실패 상세 표에 싣는 최대 행 수. 넘치면 잘렸다는 사실을 표 아래 남긴다(Step Summary 크기 보호).
DETAIL_LIMIT=20

err() { echo "[preflight] $*" >&2; }

# 호출자(워크플로)가 파싱할 요약 한 줄. HTML 주석이라 Step Summary 에는 안 보이고, stdout 한 갈래만
# 캡처하면 사람용 마크다운과 기계용 값을 함께 얻는다. 원격 실행이라 $GITHUB_OUTPUT 을 쓸 수 없다.
digest() { echo ""; echo "<!-- digest: $* -->"; }

# 0 이면 "0건", 아니면 굵게. 표에서 걸린 줄이 한눈에 튀게 한다.
fmt() { if [ "$1" -gt 0 ]; then echo "**$1건**"; else echo "0건"; fi; }

# 상세 표가 DETAIL_LIMIT 에서 잘렸다는 사실을 표 바로 아래 남긴다. 안 남기면 "20건이구나" 로 읽혀
# 조치 범위를 과소평가한다(가드 표의 총계와 어긋나 보이기도 한다).
truncated_note() {
  [ "$1" -gt "$DETAIL_LIMIT" ] && echo "총 $1건 중 상위 ${DETAIL_LIMIT}건만 표시."
  return 0
}

# --region 명시: docker run 은 호스트 리전 설정을 상속하지 않는다 (provision-runtime.sh 와 동일 고정).
ssm_param() {
  docker run --rm --network host "$AWSCLI_IMAGE" ssm get-parameter \
    --name "${SSM_PREFIX}/$1" --with-decryption \
    --region "$REGION" --query Parameter.Value --output text
}

DB_HOST="$(ssm_param db-host)" || { err "SSM db-host 조회 실패 — IAM 권한(app_ssm_read)·파라미터 존재 확인"; exit 2; }
DB_PORT="$(ssm_param db-port)" || { err "SSM db-port 조회 실패"; exit 2; }
DB_NAME="$(ssm_param db-name)" || { err "SSM db-name 조회 실패"; exit 2; }
DB_USERNAME="$(ssm_param db-username)" || { err "SSM db-username 조회 실패"; exit 2; }
# MYSQL_PWD 로 넘긴다 — -p 인자는 프로세스 목록에 노출되고 매 호출마다 경고를 뱉는다.
MYSQL_PWD="$(ssm_param db-password)" || { err "SSM db-password 조회 실패"; exit 2; }
export MYSQL_PWD

# -N -B: 헤더·표 장식 없이 탭 구분 raw 값만. 그대로 셸 변수에 담는다.
#
# 조회 실패는 여기서 종료 코드 2로 바꾼다 — 그냥 두면 mysql 의 1 이 그대로 올라와 호출자가
# "가드 위반(1)" 과 구분하지 못한다. DB 장애가 "이대로 배포하면 마이그레이션이 죽는다" 로 둔갑하는 자리다.
# 명령 치환은 그 코드를 그대로 전달하고, 상세 표의 파이프도 pipefail 덕에 2 를 그대로 올린다.
q() {
  local out
  if ! out=$(docker run --rm --network host -e MYSQL_PWD "$MYSQL_IMAGE" \
      mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USERNAME" -N -B "$DB_NAME" -e "$1"); then
    err "DB 조회 실패 (${DB_USERNAME}@${DB_HOST}:${DB_PORT}/${DB_NAME}) — SQL 첫 줄: ${1%%$'\n'*}"
    exit 2
  fi
  # 빈 결과에 개행 한 줄을 만들지 않는다 — 상세 표의 `while read` 가 빈 행 하나를 찍는다.
  [ -n "$out" ] || return 0
  printf '%s\n' "$out"
}

echo "### Preflight: prod 마이그레이션"
echo ""
echo "- 대상 \`${ENVIRONMENT}\` · DB \`${DB_NAME}@${DB_HOST}\`"

# ── 상설 가드 — 마이그레이션 종류와 무관하게 항상 ────────────────────────────
# 실패 기록이 남아 있으면 Flyway 가 그 지점에서 멈춰 이후 모든 배포가 같은 자리에서 죽는다.
FW=$(q "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0")

# ── 한시 가드 — 클론 평탄화 백필이 아직 안 나갔을 때만 ──────────────────────
# 백필의 두 check() 와 같은 조건을 같은 SQL 로 센다. 문구가 아니라 조건을 맞춰야 의미가 있으므로,
# CloneFlattenBackfill 의 가드를 고치면 여기도 함께 고친다.
APPLIED=$(q "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '${BACKFILL_VERSION}' AND success = 1")
G1=0
G2=0

if [ "$APPLIED" -gt 0 ]; then
  INSTALLED=$(q "SELECT MAX(installed_on) FROM flyway_schema_history WHERE version = '${BACKFILL_VERSION}' AND success = 1")
  echo "- 클론 평탄화 백필 \`V${BACKFILL_VERSION}\` 적용 완료 (${INSTALLED})"
  BACKFILL_STATE="applied"
else
  echo "- 클론 평탄화 백필 \`V${BACKFILL_VERSION}\` 미적용. 이번 배포에서 실행"
  BACKFILL_STATE="pending"

  # 살아있는 클론 참여자 한 명을 한 행으로 푼 공통 뷰. 백필의 loadCloneParticipants 와 같은 범위다
  # (클론·참여 행 양쪽 non-deleted). 아래 가드·예측이 전부 이 모양 위에서 돈다.
  CLONE_PARTICIPANTS="
    FROM tournaments c
    JOIN tournament_users tu ON tu.tournament_id = c.id AND tu.deleted_at IS NULL
    WHERE c.source_tournament_id IS NOT NULL AND c.deleted_at IS NULL
  "
  # 그 참여자가 ROOT 에 갖는 활성 참여 행. 있으면 멤버(병합·스킵), 없으면 링크 게스트(재지향).
  HAS_ACTIVE_ROOT="
    EXISTS (SELECT 1 FROM tournament_users r
            WHERE r.tournament_id = c.source_tournament_id AND r.user_id = tu.user_id
              AND r.id <> tu.id AND r.deleted_at IS NULL)
  "
  # ROOT 행이 이미 자기 플레이를 가졌나 (완주했거나 이력이 있나). 백필의 has_own_play 와 같다.
  HAS_OWN_PLAY="
    EXISTS (SELECT 1 FROM tournament_users r
            WHERE r.tournament_id = c.source_tournament_id AND r.user_id = tu.user_id
              AND r.id <> tu.id AND r.deleted_at IS NULL
              AND (r.completed_at IS NOT NULL
                   OR EXISTS (SELECT 1 FROM tournament_histories h
                              WHERE h.tournament_user_id = r.id AND h.deleted_at IS NULL)))
  "

  # 가드 1 — attributeNullTuHistoriesToSoleParticipant 의 check(participantCount == 1).
  # 클론에 tournament_user_id 가 빈 이력이 있는데 참여자가 1명이 아니면 소유자를 확정할 수 없다.
  G1_SQL="
    FROM tournaments c
    WHERE c.source_tournament_id IS NOT NULL AND c.deleted_at IS NULL
      AND EXISTS (SELECT 1 FROM tournament_histories h
                  WHERE h.tournament_id = c.id AND h.tournament_user_id IS NULL AND h.deleted_at IS NULL)
      AND (SELECT COUNT(*) FROM tournament_users tu
           WHERE tu.tournament_id = c.id AND tu.deleted_at IS NULL) <> 1
  "
  G1=$(q "SELECT COUNT(*) ${G1_SQL}")

  # 가드 2 — repointLinkGuest 의 check(collision == 0).
  # 링크 게스트로 판정돼 ROOT 로 옮겨야 하는데, ROOT 에 그 유저의 다른 행이 이미 있으면
  # uk_tournament_users(tournament_id, user_id) 가 깨진다. soft-deleted 행도 인덱스에 남아 함께 센다.
  G2_SQL="
    ${CLONE_PARTICIPANTS}
      AND NOT ${HAS_ACTIVE_ROOT}
      AND EXISTS (SELECT 1 FROM tournament_users r2
                  WHERE r2.tournament_id = c.source_tournament_id AND r2.user_id = tu.user_id
                    AND r2.id <> tu.id)
  "
  G2=$(q "SELECT COUNT(*) ${G2_SQL}")
fi

echo ""
echo "| 가드 | 조건 | 결과 |"
echo "|---|---|---|"
echo "| Flyway 잔재 | \`success=0\` 기록 | $(fmt "$FW") |"
if [ "$BACKFILL_STATE" = "pending" ]; then
  echo "| NULL tuId 이력 귀속 | 클론에 \`tournament_user_id\` 없는 이력 + 참여자 1명 아님 | $(fmt "$G1") |"
  echo "| 링크게스트 재지향 | ROOT 에 같은 유저 행 존재 (soft-deleted 포함) | $(fmt "$G2") |"
fi
echo ""

# ── 중단 ────────────────────────────────────────────────────────────────────
if [ "$FW" -gt 0 ] || [ "$G1" -gt 0 ] || [ "$G2" -gt 0 ]; then
  echo "**중단.** 이대로 배포하면 마이그레이션이 예외로 죽고 헬스체크 실패로 롤백됨"
  echo ""

  if [ "$FW" -gt 0 ]; then
    echo "| 버전 | 설명 | 실패 시각 |"
    echo "|---|---|---|"
    q "SELECT COALESCE(version, '-'), description, installed_on
       FROM flyway_schema_history WHERE success = 0 ORDER BY installed_rank LIMIT ${DETAIL_LIMIT}" \
      | while IFS=$'\t' read -r ver desc at; do
          echo "| \`$ver\` | $desc | $at |"
        done
    truncated_note "$FW"
    echo ""
    echo "실패 기록이 남아 있으면 Flyway 가 그 지점에서 멈춘다. 해당 행을 제거(repair)한 뒤 다시 실행."
    echo ""
  fi

  if [ "$G1" -gt 0 ]; then
    echo "| 클론 | 참여자 | NULL tuId 이력 | 막는 이유 |"
    echo "|---|---|---|---|"
    q "SELECT c.id,
              (SELECT COUNT(*) FROM tournament_users tu WHERE tu.tournament_id = c.id AND tu.deleted_at IS NULL),
              (SELECT COUNT(*) FROM tournament_histories h
               WHERE h.tournament_id = c.id AND h.tournament_user_id IS NULL AND h.deleted_at IS NULL)
       ${G1_SQL} ORDER BY c.id LIMIT ${DETAIL_LIMIT}" \
      | while IFS=$'\t' read -r cid pc hc; do
          echo "| $cid | ${pc}명 | ${hc}건 | 참여자가 1명이 아니라 이력 소유자 확정 불가 |"
        done
    truncated_note "$G1"
    echo ""
  fi

  if [ "$G2" -gt 0 ]; then
    echo "| 클론 | ROOT | 클론 TU | 막는 이유 |"
    echo "|---|---|---|---|"
    q "SELECT c.id, c.source_tournament_id, tu.id ${G2_SQL} ORDER BY c.id LIMIT ${DETAIL_LIMIT}" \
      | while IFS=$'\t' read -r cid rid tid; do
          echo "| $cid | $rid | $tid | ROOT 에 같은 유저 행 존재 |"
        done
    truncated_note "$G2"
    echo ""
  fi

  echo "조치 후 promote 를 다시 실행."
  digest "verdict=blocked backfill=${BACKFILL_STATE} fw=${FW} g1=${G1} g2=${G2}"
  exit 1
fi

# ── 통과 ────────────────────────────────────────────────────────────────────
if [ "$BACKFILL_STATE" = "applied" ]; then
  echo "**통과.** 실패한 마이그레이션 잔재 없음"
  digest "verdict=pass backfill=applied"
  exit 0
fi

echo "**통과.** 마이그레이션이 중단되지 않음"
echo ""

# 백필이 참여자를 셋 중 하나로 분류한다 — 그 예측치를 미리 보여준다. 배포 후 백필 로그
# (merged / repointed / selfCloneSkipped)와 대조하면 백필이 의도대로 돌았는지 확인된다.
REPOINT=$(q "SELECT COUNT(*) ${CLONE_PARTICIPANTS} AND NOT ${HAS_ACTIVE_ROOT}")
MERGE=$(q "SELECT COUNT(*) ${CLONE_PARTICIPANTS} AND ${HAS_ACTIVE_ROOT} AND NOT ${HAS_OWN_PLAY}")
SKIP=$(q "SELECT COUNT(*) ${CLONE_PARTICIPANTS} AND ${HAS_ACTIVE_ROOT} AND ${HAS_OWN_PLAY}")

# 백필이 손대지 않는 자리. loadCloneParticipants 가 활성 참여 행으로 INNER JOIN 하므로, 참여 행이
# 전부 soft-deleted 인 클론은 통째로 빠지고 그 이력이 클론에 남는다. 그 참여 행이 이미 지워져
# 앱에서 안 보이는 값이라 유실 영향은 없고, Phase 4 가 클론 행을 지울 때 함께 정리할 대상이다.
ORPHAN_CLONES=$(q "
  SELECT COUNT(*) FROM tournaments c
  WHERE c.source_tournament_id IS NOT NULL AND c.deleted_at IS NULL
    AND NOT EXISTS (SELECT 1 FROM tournament_users tu WHERE tu.tournament_id = c.id AND tu.deleted_at IS NULL)
    AND EXISTS (SELECT 1 FROM tournament_histories h WHERE h.tournament_id = c.id AND h.deleted_at IS NULL)")
ORPHAN_HISTORIES=$(q "
  SELECT COUNT(*) FROM tournament_histories h
  JOIN tournaments c ON c.id = h.tournament_id
  WHERE c.source_tournament_id IS NOT NULL AND c.deleted_at IS NULL AND h.deleted_at IS NULL
    AND NOT EXISTS (SELECT 1 FROM tournament_users tu WHERE tu.tournament_id = c.id AND tu.deleted_at IS NULL)")

echo "| 예상 처리 | 건수 |"
echo "|---|---|"
echo "| 재지향 (링크게스트) | ${REPOINT} |"
echo "| 병합 (초대멤버) | ${MERGE} |"
echo "| 스킵 (self·중복) | ${SKIP} |"
echo "| 대상 밖 (TU 가 이미 soft-deleted) | 클론 ${ORPHAN_CLONES} · 이력 ${ORPHAN_HISTORIES} |"

digest "verdict=pass backfill=pending repoint=${REPOINT} merge=${MERGE} selfskip=${SKIP}"
