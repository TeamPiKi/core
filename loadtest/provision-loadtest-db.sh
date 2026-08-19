#!/usr/bin/env bash
# 부하테스트 DB 박스 프로비저닝 (#911) — piki-loadtest-db 박스 위에서 실행하는 멱등 스크립트.
# 같은 디렉터리에 extractor-stub.py 가 있어야 한다 (loadtest/ 를 통째로 올려 실행).
#
# 이 박스가 둘을 든다:
#   1) MySQL (3306) — prod DB 박스와 완전 동일한 기동 인자로 미러
#   2) extractor stub (8090) — 부하테스트 앱 박스의 EXTRACT_REMOTE_BASE_URL 대상.
#      부하기가 로컬 머신이라 사설망에서 접근 가능한 박스가 여기뿐이다. sleep 위주의
#      초경량 서버라 미러 측정 간섭은 무시 수준이고, 메모리 캡 128m 으로 격리한다.
#
# MySQL 자격은 SSM 의 dev 값(/piki-core/dev/db-*)을 그대로 쓴다(provision-ssm.sh 가 loadtest 경로로 복제하는 값과 같다) —
# 그래야 윈도우 진입 때 SSM 은 db-host 하나만 바꾸면 된다. 이 박스는 IAM role 이 없어
# SSM 을 직접 못 읽으므로, 값은 실행자가 env 로 넘긴다:
#   MYSQL_DATABASE=... MYSQL_USER=... MYSQL_PASSWORD=... ./provision-loadtest-db.sh
#
# MySQL 기동 인자는 prod 와 동일하게 미러한다 — 실측 한계가 "prod 구성 그대로의 시스템
# 한계"여야 결과가 prod 로 이전된다. 박스(t4g.micro)·메모리 캡 384m·버퍼풀 64M·
# max-connections 60·performance-schema OFF 전부 prod 값(#925 이후 정본은 infra/compose/db.yml).
# 미러가 아닌 부분(의도): prod 는 compose 기동 + mysqld_exporter(32m) 동거, 여기는 docker run
# + extractor stub(128m) 동거. DB 내부 지표는 exporter 대신 러닝 중 SHOW GLOBAL STATUS 샘플링으로 본다.
set -euo pipefail

: "${MYSQL_DATABASE:?SSM /piki-core/dev/db-name 값을 env 로 넘겨라 (loadtest 경로에도 같은 값이 복제된다)}"
: "${MYSQL_USER:?SSM /piki-core/dev/db-username 값을 env 로 넘겨라}"
: "${MYSQL_PASSWORD:?SSM /piki-core/dev/db-password 값을 env 로 넘겨라}"

DIR="$(cd "$(dirname "$0")" && pwd)"
CONTAINER=piki-loadtest-mysql
VOLUME=piki-loadtest-mysql-data
IMAGE=mysql:8.4
STUB=extractor-stub

# ── 1) MySQL (prod 미러) ──
if docker ps -a --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  echo "[mysql] 컨테이너가 이미 존재 — 그대로 기동 (기동 인자를 바꾸려면 docker rm -f 후 재실행)"
  docker start "$CONTAINER" > /dev/null
else
  docker volume create "$VOLUME" > /dev/null
  # 소모품 박스라 root 비번을 앱 비번과 분리하지 않는다 (dev provision-runtime.sh 와 동일).
  docker run -d --name "$CONTAINER" --restart unless-stopped \
    --memory 384m --memory-swap 768m \
    -p 3306:3306 \
    -v "$VOLUME":/var/lib/mysql \
    -e MYSQL_DATABASE -e MYSQL_USER -e MYSQL_PASSWORD \
    -e MYSQL_ROOT_PASSWORD="$MYSQL_PASSWORD" \
    "$IMAGE" \
    --performance-schema=OFF \
    --innodb-buffer-pool-size=64M \
    --max-connections=60
fi

echo "[mysql] readiness 대기 (최대 120s)"
MYSQL_READY=0
for _ in $(seq 1 60); do
  if docker exec "$CONTAINER" mysqladmin ping -h 127.0.0.1 -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" --silent 2> /dev/null; then
    MYSQL_READY=1
    echo "[mysql] ready — 앱 접속 계정 검증 완료"
    break
  fi
  sleep 2
done
if [ "$MYSQL_READY" -ne 1 ]; then
  echo "[mysql] 준비 안 됨 — docker logs $CONTAINER 확인" >&2
  exit 1
fi

# ── 2) extractor stub (동거, 8090) ──
# 코드 갱신 반영을 위해 항상 재생성한다 (상태 없음, 재생성 비용 0).
docker pull python:3.12-alpine
if docker ps -a --format '{{.Names}}' | grep -qx "$STUB"; then
  docker rm -f "$STUB" > /dev/null
fi
docker run -d --name "$STUB" --restart unless-stopped \
  --memory 128m \
  -p 8090:8090 \
  -v "$DIR/extractor-stub.py":/stub.py:ro \
  -e STUB_DELAY_MIN_MS -e STUB_DELAY_MAX_MS \
  python:3.12-alpine python /stub.py

echo "[stub] 기동 검증"
sleep 2
curl -sf http://localhost:8090/actuator/health > /dev/null
echo "[stub] OK — 부하테스트 앱 박스에서 http://<이 박스 사설IP>:8090/actuator/health 로 재검증할 것"
