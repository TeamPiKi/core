#!/usr/bin/env bash
# DB 박스 런타임 프로비저닝 — 멱등(idempotent). (#898)
#
# terraform 의 user_data 는 docker·swap 까지만 깔고 끝난다. MySQL 기동과 백업 cron 설치는
# 여기가 맡는다 — user_data 는 첫 부팅에만 실행돼 스크립트를 고쳐도 반영되지 않고, 반영하려면
# 인스턴스 교체가 필요한데 이 박스에서 교체는 곧 데이터 손실이기 때문이다.
#
# 실행: 같은 디렉터리의 db-backup.sh 와 함께 박스로 올린 뒤 이 스크립트를 돌린다.
#   scp -i ~/.ssh/piki-ec2-connect infra/scripts/{provision-db.sh,db-backup.sh} ubuntu@<DB_PUBLIC_IP>:/tmp/
#   ssh  -i ~/.ssh/piki-ec2-connect ubuntu@<DB_PUBLIC_IP> 'bash /tmp/provision-db.sh'
# (키페어가 없는 박스라 접속 전에 EC2 Instance Connect 로 공개키를 밀어 넣어야 한다 — README 참고.)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AWSCLI_IMAGE="public.ecr.aws/aws-cli/aws-cli:2.35.21"
MYSQL_IMAGE="mysql:8.4"
CONTAINER="piki-prod-mysql"
VOLUME="piki-prod-mysql-data"
REGION="ap-northeast-2"
SSM_PREFIX="/piki-core/prod"

# 컨테이너 메모리 캡. t4g.micro(실가용 약 906MB)에서 OS·docker 몫을 남기려면 상한이 필요하다.
# 384m 은 dev 박스 같은 구성의 실사용(111MB)의 3배 이상이고, InnoDB 버퍼풀 기본값(128MB)에
# 데이터 전량(2.1MB)이 올라가는 상태를 여유 있게 덮는다. swap 은 캡의 2배로 둬 순간 초과를 흡수한다.
MEM_LIMIT="384m"
MEM_SWAP="768m"

ssm_param() {
  docker run --rm --network host "$AWSCLI_IMAGE" ssm get-parameter \
    --name "${SSM_PREFIX}/$1" --with-decryption \
    --region "$REGION" --query Parameter.Value --output text
}

# ── 1) MySQL ────────────────────────────────────────────────────────────────
# 자격증명은 컨테이너를 "최초 생성"할 때만 쓰인다. 이미 있으면 값이 안 쓰이지만 조회는 매번 한다 —
# 재생성 때만 도는 경로로 두면 조용히 썩은 채 가장 필요한 순간에 터진다(provision-runtime.sh 와 같은 규율).
DB_NAME="$(ssm_param db-name)" || { echo "[mysql] SSM db-name 조회 실패 — IAM(piki-prod-db-policy)·파라미터 존재 확인"; exit 1; }
DB_USERNAME="$(ssm_param db-username)" || { echo "[mysql] SSM db-username 조회 실패"; exit 1; }
DB_PASSWORD="$(ssm_param db-password)" || { echo "[mysql] SSM db-password 조회 실패"; exit 1; }
echo "[mysql] DB 자격증명 SSM 로드 완료 (${SSM_PREFIX}/db-*)"

if docker ps -a --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  echo "[mysql] ${CONTAINER} 이미 존재 — skip"
else
  echo "[mysql] ${CONTAINER} 기동 (named volume: ${VOLUME})"
  # 포트는 모든 인터페이스에 연다. dev 박스가 172.17.0.1 로 묶는 것과 달리 여기서는 앱이 다른
  # 박스에서 사설 IP 로 접속하기 때문이다. 노출 범위는 보안그룹이 책임진다 — 3306 인바운드는
  # 앱 EC2 SG 에서 온 것만 허용한다(terraform/db_ec2.tf).
  #
  # 아래 세 튜닝은 기본값이 이 박스 크기에 안 맞아서 넣는다. 기본값으로 띄웠을 때 실측이
  # RAM 234MB + swap 185MB = 약 419MB 로 캡(384m)을 넘어 상시 스왑 상태였다.
  #   --performance-schema=OFF   기본 ON 이고 이 구성에서 가장 큰 몫이다. 앱 지표는 Micrometer·
  #                              Grafana 로 따로 받고 있어 DB 내부 계측을 켜 둘 실익이 없다.
  #                              (진단이 필요하면 그때 켜서 재기동한다.)
  #   --innodb-buffer-pool-size  기본 128M. 데이터 총량이 2.1MB 라 64M 에도 전량이 캐시된다.
  #   --max-connections          기본 151. 앱 HikariCP 는 기본 풀 10 이고 blue-green 전환 때만
  #                              잠시 2배가 되므로, 백업·관리 여유를 포함해도 60 이면 남는다.
  # 값을 바꾸면 컨테이너 재생성이 필요하다(옵션은 기동 인자다). 데이터는 named volume 에 있어
  # 재생성 자체는 안전하지만, 재생성 동안 앱 연결이 끊기므로 배포 창을 잡아 수행한다.
  docker run -d \
    --name "$CONTAINER" \
    --restart unless-stopped \
    --memory "$MEM_LIMIT" \
    --memory-swap "$MEM_SWAP" \
    -p 3306:3306 \
    -v "${VOLUME}:/var/lib/mysql" \
    -e MYSQL_DATABASE="$DB_NAME" \
    -e MYSQL_USER="$DB_USERNAME" \
    -e MYSQL_PASSWORD="$DB_PASSWORD" \
    -e MYSQL_ROOT_PASSWORD="$DB_PASSWORD" \
    "$MYSQL_IMAGE" \
    --performance-schema=OFF \
    --innodb-buffer-pool-size=64M \
    --max-connections=60
fi

# readiness — 최초 기동은 DB·유저 생성 후 내부 재시작이 있어, 바로 접속하면 실패한다.
echo "[mysql] readiness 대기"
for i in $(seq 1 30); do
  if docker exec "$CONTAINER" mysqladmin ping -h 127.0.0.1 --silent 2>/dev/null; then
    echo "[mysql] ready (attempt $i)"
    break
  fi
  sleep 2
  [ "$i" -eq 30 ] && { echo "[mysql] readiness timeout (60s)"; exit 1; }
done

# ── 2) 백업 스크립트 + cron ─────────────────────────────────────────────────
# RDS 의 자동 백업을 대신하는 유일한 복구 경로다. 매 실행마다 최신본으로 덮어써
# repo 의 스크립트와 박스의 것이 어긋나지 않게 한다.
if [ ! -f "${SCRIPT_DIR}/db-backup.sh" ]; then
  echo "[backup] ${SCRIPT_DIR}/db-backup.sh 가 없다 — provision-db.sh 와 함께 올렸는지 확인"
  exit 1
fi
sudo install -m 0755 "${SCRIPT_DIR}/db-backup.sh" /usr/local/bin/piki-db-backup.sh
echo "[backup] /usr/local/bin/piki-db-backup.sh 설치"

# 19:00 UTC = 04:00 KST. 트래픽이 가장 적은 시간대에 둔다(백업 자체는 무중단이지만
# 혹시 모를 부하도 한산할 때 지나가게 한다).
# 출력은 journald 로 보내 `journalctl -t piki-db-backup` 으로 성공·실패를 추적한다 —
# cron 기본 동작인 로컬 메일은 이 박스에 MTA 가 없어 사라진다.
#
# bash -o pipefail 로 감싸는 이유: 파이프라인의 종료 코드는 마지막 명령(logger)의 것이라,
# 그냥 파이프하면 백업이 실패해도 cron 이 보는 결과는 항상 성공이 된다. logger 는 거의 언제나
# 0 을 반환하기 때문이다. pipefail 이 있어야 백업 스크립트의 실패 코드가 그대로 올라와,
# 나중에 실패 감지(cron 모니터링·systemd timer 등)를 붙일 때 그것이 실제로 동작한다.
CRON_LINE='0 19 * * * /bin/bash -o pipefail -c "/usr/local/bin/piki-db-backup.sh 2>&1 | /usr/bin/logger -t piki-db-backup"'
if sudo crontab -l 2>/dev/null | grep -qF 'piki-db-backup.sh'; then
  echo "[backup] cron 이미 등록 — 내용 갱신"
  sudo crontab -l 2>/dev/null | grep -vF 'piki-db-backup.sh' | { cat; echo "$CRON_LINE"; } | sudo crontab -
else
  echo "[backup] cron 등록 (매일 19:00 UTC = 04:00 KST)"
  sudo crontab -l 2>/dev/null | { cat; echo "$CRON_LINE"; } | sudo crontab -
fi

echo "DB 박스 프로비저닝 완료"
echo "  백업 수동 실행: sudo /usr/local/bin/piki-db-backup.sh"
echo "  백업 로그 확인: journalctl -t piki-db-backup --since '1 day ago'"
