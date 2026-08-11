#!/usr/bin/env bash
# DB 박스 런타임 프로비저닝 — 멱등(idempotent). (#898)
#
# terraform 의 user_data 는 docker·swap 까지만 깔고 끝난다. MySQL 기동과 백업 cron 설치는
# 여기가 맡는다 — user_data 는 첫 부팅에만 실행돼 스크립트를 고쳐도 반영되지 않고, 반영하려면
# 인스턴스 교체가 필요한데 이 박스에서 교체는 곧 데이터 손실이기 때문이다.
#
# 실행: 이 스크립트와 db-backup.sh, 그리고 공용 alloy 블록을 함께 박스로 올린 뒤 돌린다.
#   scp -i ~/.ssh/piki-ec2-connect infra/scripts/{provision-db.sh,db-backup.sh} ubuntu@<DB_PUBLIC_IP>:/tmp/
#   scp -i ~/.ssh/piki-ec2-connect -r <infra-repo>/blocks/alloy ubuntu@<DB_PUBLIC_IP>:/tmp/
#   ssh  -i ~/.ssh/piki-ec2-connect ubuntu@<DB_PUBLIC_IP> 'bash /tmp/provision-db.sh'
# (키페어가 없는 박스라 접속 전에 EC2 Instance Connect 로 공개키를 밀어 넣어야 한다 — README 참고.)
#
# alloy 블록을 core 에 복사해 두지 않고 매번 infra 에서 올리는 이유는 배포 워크플로와 같다 —
# 그 블록의 SSOT 는 TeamPiKi/infra 이고, 사본을 두면 조용히 갈라진다. 안 올리면 4절이 건너뛴다.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# --skip-alloy: 관측 수집기 설치를 의도적으로 생략한다(긴급 복구 등). 기본은 생략하지 않으며,
# 블록이 없으면 실패한다 — 관측 공백이 조용히 생기는 걸 막는 게 기본값이어야 한다.
SKIP_ALLOY=false
for arg in "$@"; do
  case "$arg" in
    --skip-alloy) SKIP_ALLOY=true ;;
    *) echo "알 수 없는 인자: $arg" >&2; exit 2 ;;
  esac
done

AWSCLI_IMAGE="public.ecr.aws/aws-cli/aws-cli:2.35.21"
# 이미지 태그와 데이터 볼륨 이름은 compose 파일이 갖는다. 여기 남는 CONTAINER 는 SQL 실행·검증에
# docker exec 로 부를 때 쓰는 이름이라, compose 쪽 container_name 과 같은 값을 유지해야 한다.
CONTAINER="piki-prod-mysql"
REGION="ap-northeast-2"
SSM_PREFIX="/piki-core/prod"

# 컨테이너 정의(이미지·포트·메모리 캡·MySQL 튜닝·라벨)는 전부 compose 파일에 있다(#918).
# 여기 남는 것은 그 파일을 찾는 좌표와, SQL·검증에서 쓰는 이름뿐이다.
#
# 두 위치를 찾는다. repo 에서는 scripts/ 와 compose/ 가 형제라 ../compose 이고, 박스로 올릴
# 때는 보통 한 디렉터리에 몰아 넣어 나란히 놓인다. 어느 쪽이든 돌게 해서 "올리는 방식"이
# 스크립트 동작을 좌우하지 않게 한다.
if [ -f "${SCRIPT_DIR}/../compose/db.yml" ]; then
  COMPOSE_FILE="${SCRIPT_DIR}/../compose/db.yml"
else
  COMPOSE_FILE="${SCRIPT_DIR}/compose/db.yml"
fi
# 프로젝트명을 고정한다. compose 는 기본적으로 파일이 있는 디렉터리명을 쓰는데, 스크립트를
# 어디에 두고 실행하느냐에 따라 그 값이 달라져 별개 스택으로 갈라진다.
COMPOSE_PROJECT="piki-db"
EXPORTER_CONTAINER="piki-mysqld-exporter"
EXPORTER_USER="exporter"

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
# root 는 앱 계정과 다른 비밀번호를 쓴다. 같은 값을 공유하면 앱 자격증명이 새는 순간 DB 전체
# 권한까지 함께 넘어간다 — 앱은 자기 스키마에만 권한이 있는 계정으로 붙고, root 는 백업·관리용으로만 남긴다.
DB_ROOT_PASSWORD="$(ssm_param db-root-password)" || { echo "[mysql] SSM db-root-password 조회 실패"; exit 1; }
echo "[mysql] DB 자격증명 SSM 로드 완료 (${SSM_PREFIX}/db-*)"

# exporter 자격증명도 여기서 함께 읽는다. compose 가 두 서비스를 한 번에 올리므로 필요한 값이
# 모두 갖춰진 뒤에 호출해야 한다.
DB_EXPORTER_PASSWORD="$(ssm_param db-exporter-password)" || { echo "[exporter] SSM db-exporter-password 조회 실패"; exit 1; }
# 아래 SQL 은 비밀번호를 문자열 리터럴로 끼워 넣는다. 값에 작은따옴표나 역슬래시가 있으면
# 문장이 깨지거나 의도치 않은 SQL 이 되므로, 그런 값은 아예 거부한다.
#
# 이스케이프 대신 거부를 택한 이유: 셸에서 MySQL 의 이스케이프 규칙을 정확히 재현하려다
# 틀리면 조용히 잘못된 비밀번호가 설정된다. 이 값은 우리가 만드는 것이라(openssl rand -hex)
# 특수문자가 낄 이유가 없고, 낀다면 그건 누군가 다른 방식으로 넣었다는 신호다.
case "$DB_EXPORTER_PASSWORD" in
  *\'*|*\\*)
    echo "[exporter] db-exporter-password 에 따옴표나 역슬래시가 있다 — SQL 리터럴로 다룰 수 없다."
    echo "[exporter] openssl rand -hex 24 처럼 영숫자만으로 재발급한 뒤 SSM 을 갱신할 것."
    exit 1 ;;
esac

# 컨테이너 기동은 compose 에 맡긴다. "이미 있으면 skip" 을 손으로 짜지 않아도 되고, 정의가
# 스크립트가 아니라 파일 하나에 모여 무엇이 떠 있어야 하는지 한눈에 드러난다.
#
# 데이터 볼륨은 compose 파일에서 external 로 선언돼 있다. 그게 없으면 compose 가 프로젝트명을
# 앞에 붙인 새 볼륨을 만들어 빈 DB 로 뜬다 — 이 전환에서 가장 조심할 지점이다.
if [ ! -f "$COMPOSE_FILE" ]; then
  echo "[mysql] ${COMPOSE_FILE} 가 없다 — provision-db.sh 와 함께 올렸는지 확인"
  exit 1
fi
echo "[mysql] compose 로 컨테이너 상태 맞추는 중 (${COMPOSE_FILE})"
DB_NAME="$DB_NAME" DB_USERNAME="$DB_USERNAME" DB_PASSWORD="$DB_PASSWORD" \
DB_ROOT_PASSWORD="$DB_ROOT_PASSWORD" DB_EXPORTER_PASSWORD="$DB_EXPORTER_PASSWORD" \
  docker compose --project-name "$COMPOSE_PROJECT" --file "$COMPOSE_FILE" up -d --wait \
  || { echo "[mysql] compose up 실패"; exit 1; }
echo "[mysql] compose up 완료"

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

# 자격증명이 실제로 통하는지 확인한다. 위 ping 은 서버 응답만 보고 인증을 안 거치므로,
# 그것만으로 "프로비저닝 성공"을 말하면 거짓이 될 수 있다.
#
# 이 검사가 필요한 이유: MYSQL_* 환경변수는 컨테이너를 "최초 생성"할 때만 쓰인다. 그 뒤 SSM 값을
# 바꾸면 DB 안 계정은 옛 비밀번호로 남고 SSM 만 새 값이 되어, 둘이 조용히 갈라진다. 그 상태로
# 스크립트가 성공하면 다음에 그 자격증명을 쓰는 쪽(앱 배포·백업 cron)이 대신 터진다.
#
# 어긋났을 때 여기서 자동으로 회전하지는 않는다. 앱 계정 비밀번호를 스크립트가 임의로 바꾸면
# 붙어 있던 커넥션이 끊기므로, 회전은 사람이 배포 창에서 절차대로 수행할 일이다.
for role in root app; do
  case "$role" in
    root) user=root;           pw="$DB_ROOT_PASSWORD" ;;
    app)  user="$DB_USERNAME"; pw="$DB_PASSWORD" ;;
  esac
  if ! docker exec -e MYSQL_PWD="$pw" "$CONTAINER" mysql -u "$user" -e "SELECT 1" >/dev/null 2>&1; then
    echo "[mysql] ${role}(${user}) 자격증명이 SSM 값과 어긋난다 — 컨테이너 안 계정은 최초 생성 시의 비밀번호를 유지한다."
    echo "[mysql] 회전 절차: docker exec -it ${CONTAINER} mysql -uroot -p 로 접속해"
    echo "[mysql]            ALTER USER '<user>'@'<host>' IDENTIFIED BY '<SSM 값>'; 을 실행한 뒤 이 스크립트를 다시 돌린다."
    exit 1
  fi
done
echo "[mysql] 자격증명 검증 완료 (root·${DB_USERNAME} 모두 SSM 값으로 접속 가능)"

# ── 1b) 지표 수집용 DB 계정 ─────────────────────────────────────────────────
# RDS 가 CloudWatch 로 주던 DB 내부 지표(연결 수·처리량 등)를 대신 걷는다(#912). exporter 에
# root 를 물리지 않고 전용 계정을 둔다 - 수집기는 상시 붙어 있는 프로세스라, 뚫렸을 때 넘어가는
# 권한이 작아야 한다. PROCESS·REPLICATION CLIENT 는 상태 조회에 필요한 최소 권한이고 데이터는
# 읽지 못한다.
#
# 계정의 host 는 docker 브리지 게이트웨이다. 수집기가 host 네트워크에서 127.0.0.1:3306 으로
# 붙어도, MySQL 컨테이너의 포트 매핑(-p)을 지나며 SNAT 되어 MySQL 에는 게이트웨이 주소로 보인다.
# 그래서 127.0.0.1 로 만들면 Access denied 가 난다(실측). 값을 박지 않고 조회하는 이유는
# 브리지 대역이 환경마다 다를 수 있어서다.
#
# 위 앱·root 계정과 달리 비밀번호를 매번 SSM 값으로 맞춘다(ALTER USER). 이 계정은 앱이 쓰지
# 않아 재설정해도 끊길 커넥션이 수집기 하나뿐이고, 그 편이 SSM 과 실제가 갈라지는 걸 원천 차단한다.
# (값과 형식 검사는 compose 호출 전에 이미 끝냈다.)
# 기본 브리지(bridge)가 아니라 MySQL 컨테이너가 실제로 붙은 네트워크의 게이트웨이를 본다.
# compose 는 프로젝트마다 전용 네트워크를 만들어(172.18.x 등) 기본 브리지(172.17.x)와 대역이
# 다르다. `docker network inspect bridge` 로 조회하면 엉뚱한 주소가 나와 Access denied 가 난다
# (스크립트에서 compose 로 옮기면서 실제로 겪었다). 컨테이너를 직접 물어보면 어느 네트워크에
# 있든 맞는 값을 얻는다.
DOCKER_GW="$(docker inspect "$CONTAINER" --format '{{range $k, $v := .NetworkSettings.Networks}}{{$v.Gateway}}{{end}}')" \
  || { echo "[exporter] ${CONTAINER} 네트워크 게이트웨이 조회 실패"; exit 1; }
[ -n "$DOCKER_GW" ] || { echo "[exporter] 게이트웨이가 비어 있다"; exit 1; }
# 옛 127.0.0.1 계정은 접속에 안 쓰이므로 정리한다(초기 구현 잔재).
printf "DROP USER IF EXISTS '%s'@'127.0.0.1';
CREATE USER IF NOT EXISTS '%s'@'%s' IDENTIFIED BY '%s';
ALTER USER '%s'@'%s' IDENTIFIED BY '%s';
GRANT PROCESS, REPLICATION CLIENT ON *.* TO '%s'@'%s';
FLUSH PRIVILEGES;\n" \
  "$EXPORTER_USER" \
  "$EXPORTER_USER" "$DOCKER_GW" "$DB_EXPORTER_PASSWORD" \
  "$EXPORTER_USER" "$DOCKER_GW" "$DB_EXPORTER_PASSWORD" \
  "$EXPORTER_USER" "$DOCKER_GW" \
  | docker exec -i -e MYSQL_PWD="$DB_ROOT_PASSWORD" "$CONTAINER" mysql -uroot \
  || { echo "[exporter] DB 계정 생성·갱신 실패"; exit 1; }
echo "[exporter] DB 계정 준비 완료 (${EXPORTER_USER}@${DOCKER_GW}, PROCESS·REPLICATION CLIENT)"

# exporter 컨테이너 자체는 위 compose 가 이미 올렸다. 여기서는 그것이 실제로 DB 에 붙어
# 지표를 내는지만 확인한다 — 계정이 방금 만들어졌으므로, compose 가 먼저 올린 exporter 는
# 초기 몇 초간 인증에 실패할 수 있다(재시도로 흡수된다).
#
# 수집기가 실제로 DB 에 붙어 지표를 내는지 확인한다. 컨테이너가 떠 있다는 것만으로는
# 자격증명·권한이 맞는지 알 수 없고, 그 상태로 두면 대시보드가 조용히 빈다.
#
# mysql_up 만 보지 않는다. 그 값은 접속 성공만 뜻해서, 권한이 모자라 상태 조회가 막히거나
# collector 구성이 바뀌어 지표가 안 나와도 1 이 될 수 있다. 실제로 쓰는 계열이 값을 내는지까지
# 확인해야 "수집된다"고 말할 수 있다. 전수 검사는 하지 않는다 - 소비하는 지표 목록이 아직
# 없고, 계열이 늘 때마다 이 목록을 따라 고치는 비용이 이득을 넘는다.
#
# performance_schema 기반 계열은 넣지 않는다. #898 에서 메모리 때문에 껐으므로 없는 게 정상이다.
EXPORTER_REQUIRED_METRICS="mysql_up mysql_global_status_threads_connected mysql_global_status_uptime"
check_exporter_metrics() {
  local body
  # timeout 을 넉넉히 둔다. exporter 는 /metrics 요청을 받을 때 MySQL 에 쿼리하므로 응답이
  # 즉시 오지 않는다. 짧게 잡으면 앞부분(mysql_up)만 받고 뒤쪽 계열이 잘린 채 "누락"으로
  # 오판한다(3초로 뒀다가 실제로 겪었다).
  body="$(curl -fsS --max-time 10 http://127.0.0.1:9104/metrics 2>/dev/null)" || return 1
  # here-string 으로 넘긴다. `printf ... | grep -q` 로 쓰면 grep 이 첫 매칭에서 즉시 끝나며
  # printf 가 SIGPIPE 를 받고, set -o pipefail 이 그것을 파이프라인 실패로 집는다. 즉 매칭에
  # 성공했는데도 실패로 판정된다(응답이 180KB 라 실제로 걸렸다). here-string 은 파이프가 아니라
  # 이 함정이 없다.
  grep -q '^mysql_up 1$' <<< "$body" || return 1
  local m
  for m in $EXPORTER_REQUIRED_METRICS; do
    grep -q "^${m} " <<< "$body" || { MISSING_METRIC="$m"; return 1; }
  done
  return 0
}
for i in $(seq 1 15); do
  if check_exporter_metrics; then
    echo "[exporter] 수집 확인 (mysql_up=1 + 필수 계열 존재, attempt $i)"
    break
  fi
  sleep 2
  if [ "$i" -eq 15 ]; then
    echo "[exporter] 수집이 확인되지 않는다 (누락 계열: ${MISSING_METRIC:-mysql_up}) — 계정 권한·접속 경로 확인"
    docker logs "$EXPORTER_CONTAINER" --tail 10 2>&1 | sed 's/^/[exporter] /'
    exit 1
  fi
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

# 백업 결과 지표가 놓일 자리. alloy 의 node_exporter textfile collector 가 이 디렉터리를
# 마운트해 읽는다(#905). 미리 만들어 두는 이유는 두 가지다 - 백업이 root 로 돌아 여기 쓸 수
# 있어야 하고, 디렉터리가 없으면 alloy 마운트가 빈 경로를 잡아 지표가 조용히 사라진다.
sudo mkdir -p /var/lib/node_exporter/textfile
sudo chmod 755 /var/lib/node_exporter/textfile
echo "[backup] 지표 디렉터리 준비 (/var/lib/node_exporter/textfile)"

# 15:00 UTC = 00:00 KST(자정). 처음엔 04:00 KST 였는데, 그 시각을 고를 이유가 사실상
# 없었다 - 데이터가 2.1MB 라 백업이 2초에 끝나고 --single-transaction 이라 락도 안 건다.
# 반대로 새벽에 실패하면 아침까지 방치되므로, 사람이 알림을 볼 수 있는 시간으로 옮긴다.
# 출력은 journald 로 보내 `journalctl -t piki-db-backup` 으로 성공·실패를 추적한다 —
# cron 기본 동작인 로컬 메일은 이 박스에 MTA 가 없어 사라진다.
#
# bash -o pipefail 로 감싸는 이유: 파이프라인의 종료 코드는 마지막 명령(logger)의 것이라,
# 그냥 파이프하면 백업이 실패해도 cron 이 보는 결과는 항상 성공이 된다. logger 는 거의 언제나
# 0 을 반환하기 때문이다. pipefail 이 있어야 백업 스크립트의 실패 코드가 그대로 올라와,
# 나중에 실패 감지(cron 모니터링·systemd timer 등)를 붙일 때 그것이 실제로 동작한다.
CRON_LINE='0 15 * * * /bin/bash -o pipefail -c "/usr/local/bin/piki-db-backup.sh 2>&1 | /usr/bin/logger -t piki-db-backup"'
# 기존 항목을 걷어낸 뒤 최신 줄을 다시 넣는다 — 등록/갱신을 가르지 않아야 멱등이 단순해진다.
#
# `|| true` 가 필요한 이유: crontab 에 우리 줄만 있으면 grep -v 의 출력이 0건이라 종료 코드가 1 이고,
# set -euo pipefail 이 그걸 실패로 보고 스크립트를 끊는다. 즉 이 가드가 없으면 "두 번째 실행부터
# 조용히 죽는" 스크립트가 된다(첫 실행은 crontab 이 비어 이 경로를 안 타므로 드러나지 않는다).
echo "[backup] cron 등록·갱신 (매일 15:00 UTC = 00:00 KST)"
{ sudo crontab -l 2>/dev/null | grep -vF 'piki-db-backup.sh' || true; echo "$CRON_LINE"; } | sudo crontab -

# ── 3) 관측 수집기(alloy) ───────────────────────────────────────────────────
# RDS 를 걷어내면서 잃은 것을 메운다. 관리형일 때는 CloudWatch 가 메모리·연결 수·디스크 여유·
# 쿼리 지연을 자동으로 줬는데, EC2 기본 메트릭에는 메모리조차 없다. 이 박스가 죽어가는 걸
# 알아챌 수단이 아예 없어지는 셈이라, 다른 박스(core·extractor·renderer)와 같은 수집기를 붙인다.
#
# 이 박스에서 실제로 걷히는 것은 호스트 메트릭(CPU·메모리·디스크)뿐이다. 블록의 수집 대상은
# docker label(piki.observe) opt-in 인데 MySQL 컨테이너에는 그 라벨이 없다 — redis·mysql 로그를
# 빼는 기존 정책과 같은 결이라 여기서도 유지한다(로그 볼륨). MySQL 내부 지표(연결 수·쿼리 지연)가
# 필요해지면 mysqld_exporter 를 따로 붙이는 별도 작업이다.
ALLOY_DIR="${SCRIPT_DIR}/alloy"
if [ "$SKIP_ALLOY" = true ]; then
  echo "[alloy] --skip-alloy 지정 — 수집기 설치를 건너뛴다"
elif [ ! -f "${ALLOY_DIR}/provision-alloy.sh" ]; then
  # 조용히 건너뛰지 않는다. 관측이 빠진 채 "프로비저닝 완료"가 찍히면 그 공백을 아무도 모른다 —
  # 실제로 이 박스는 관측 없이 며칠 돌 뻔했다. 의도적으로 생략할 때만 --skip-alloy 로 명시한다.
  echo "[alloy] ${ALLOY_DIR}/provision-alloy.sh 가 없다 — infra 의 blocks/alloy 를 함께 올릴 것"
  echo "[alloy] 의도적으로 생략하려면 --skip-alloy 를 붙여 실행한다"
  exit 1
else
  obs_param() {
    docker run --rm --network host "$AWSCLI_IMAGE" ssm get-parameter \
      --name "/piki/observability/$1" --with-decryption \
      --region "$REGION" --query Parameter.Value --output text
  }
  # 필수 5종은 하나라도 없으면 중단한다. 빈 값으로 넘기면 provision-alloy.sh 가 skip(성공 0)으로
  # 조용히 지나가, 관측이 없는 상태가 "설치 완료"로 보고된다.
  GRAFANA_METRICS_URL="$(obs_param grafana-metrics-url)" || { echo "[alloy] SSM grafana-metrics-url 조회 실패"; exit 1; }
  GRAFANA_METRICS_USER="$(obs_param grafana-metrics-user)" || { echo "[alloy] SSM grafana-metrics-user 조회 실패"; exit 1; }
  GRAFANA_LOGS_URL="$(obs_param grafana-logs-url)" || { echo "[alloy] SSM grafana-logs-url 조회 실패"; exit 1; }
  GRAFANA_LOGS_USER="$(obs_param grafana-logs-user)" || { echo "[alloy] SSM grafana-logs-user 조회 실패"; exit 1; }
  GRAFANA_CLOUD_TOKEN="$(obs_param grafana-cloud-token)" || { echo "[alloy] SSM grafana-cloud-token 조회 실패"; exit 1; }
  # 트레이스는 이 박스에 앱이 없어 쓰이지 않는다. 빈 값이어도 블록이 무해 처리한다.
  GRAFANA_TRACES_URL="$(obs_param grafana-traces-url)" || GRAFANA_TRACES_URL=""
  GRAFANA_TRACES_USER="$(obs_param grafana-traces-user)" || GRAFANA_TRACES_USER=""
  export GRAFANA_METRICS_URL GRAFANA_METRICS_USER GRAFANA_LOGS_URL GRAFANA_LOGS_USER
  export GRAFANA_TRACES_URL GRAFANA_TRACES_USER GRAFANA_CLOUD_TOKEN
  echo "[alloy] Grafana 자격 SSM 로드 완료"

  # --box piki-db: 호스트 메트릭에는 service/instance 축이 없어, 같은 environment 의 박스들을
  # 이 라벨로만 가른다. 대시보드에서 DB 박스를 골라 보려면 이 값이 유일한 구분자다.
  bash "${ALLOY_DIR}/provision-alloy.sh" \
    --config "${ALLOY_DIR}/config.alloy" \
    --name piki-alloy \
    --environment prod \
    --box piki-db
fi

echo "DB 박스 프로비저닝 완료"
echo "  백업 수동 실행: sudo /usr/local/bin/piki-db-backup.sh"
echo "  백업 로그 확인: journalctl -t piki-db-backup --since '1 day ago'"
