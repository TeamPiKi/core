#!/usr/bin/env bash
# EC2 런타임 프로비저닝 — 멱등(idempotent). 배포 때 실행되어 박스 안 런타임 설정
# (swap / redis / nginx default / grafana alloy)이 레포 정의 상태가 되도록 보장한다.
# swap·redis·nginx 는 이미 있으면 skip 하고, alloy 는 config 가 레포에서 오므로 매 배포 갱신·재기동한다. (#217)
#
# docker 명령은 sudo 없이(ubuntu 가 docker 그룹), 시스템·nginx 는 sudo 로 — deploy.yml 기존 패턴과 동일.
set -euo pipefail

# 1) swap — 메모리 906Mi 라 1G swap 이 필수다. 없을 때만 생성하고 fstab 에 등록해 재부팅에도 유지되게 한다.
if sudo swapon --show | grep -q '/swapfile'; then
  echo "[swap] 이미 활성 — skip"
else
  echo "[swap] /swapfile 1G 생성"
  sudo fallocate -l 1G /swapfile || sudo dd if=/dev/zero of=/swapfile bs=1M count=1024
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile
  sudo swapon /swapfile
  grep -q '/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
fi

# 1b) swappiness — swap 은 비상 쿠션으로만 쓰고 라이브 JVM heap 은 RAM 에 유지한다. 기본 60 은
# 너무 공격적이라 평시에도 JVM 이 swap 으로 밀려 GC 가 느려진다(실측 ~140Mi swap). 10 으로 낮춰
# 커널이 anon(힙)보다 page cache 를 먼저 회수하게 한다. sysctl 드롭인으로 영속화 + 즉시 적용(멱등).
if [ "$(cat /proc/sys/vm/swappiness)" = "10" ]; then
  echo "[swappiness] 이미 10 — skip"
else
  echo "[swappiness] 60 → 10 설정"
  echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/99-swappiness.conf
  sudo sysctl -w vm.swappiness=10
fi

# 1c) swap 추가 증설 — overlap(blue+green) 때 1G swap 이 100% 꽉 차는 게 실측됐다(#420 검증 배포). 2G 를
# 별도 파일로 더해 총 3G 로 만든다. 기존 /swapfile 을 리사이즈(swapoff)하지 않는 이유: swap 사용량이
# free RAM 보다 클 때 swapoff 는 페이지를 RAM 으로 못 옮겨 OOM 위험이 있다(라이브 박스). 추가 파일은 안전·멱등.
# swapon 활성 체크는 생성·활성화만 가르고, fstab 영속화는 활성 여부와 무관하게 항상 보장한다 —
# "활성이지만 fstab 누락" 상태면 재부팅에 swap2 가 사라져 완화가 무력화되기 때문.
if sudo swapon --show | grep -q '/swapfile2'; then
  echo "[swap2] 이미 활성"
else
  echo "[swap2] /swapfile2 2G 추가 (총 3G)"
  sudo fallocate -l 2G /swapfile2 || sudo dd if=/dev/zero of=/swapfile2 bs=1M count=2048
  sudo chmod 600 /swapfile2
  sudo mkswap /swapfile2
  sudo swapon /swapfile2
fi
grep -q '^/swapfile2[[:space:]]' /etc/fstab || echo '/swapfile2 none swap sw 0 0' | sudo tee -a /etc/fstab

# 2) redis — RefreshToken 저장소(RedisRefreshTokenStore). 없을 때만 named 볼륨(team3-redis-data)으로 기동.
#    기존 컨테이너(익명 볼륨 포함)는 보존한다 — 멱등 skip. 새 인스턴스에서만 named 볼륨으로 생성돼,
#    이후 컨테이너 재생성에도 refresh token 이 유지된다.
if docker ps -a --format '{{.Names}}' | grep -qx 'team3-redis'; then
  echo "[redis] team3-redis 이미 존재 — skip"
else
  echo "[redis] team3-redis named 볼륨으로 기동"
  docker run -d \
    --name team3-redis \
    --restart unless-stopped \
    -p 172.17.0.1:6379:6379 \
    -v team3-redis-data:/data \
    redis:7-alpine
fi

# 3) mysql (dev·staging 전용) — prod 는 RDS 를 쓰므로 dev/staging 일 때만 로컬 컨테이너로 기동.
#    redis 와 동일하게 named 볼륨 + 있으면 skip 패턴. 초기 자격증명은 앱이 쓰는 것과 같은 SSM 값에서 읽는다.
#    포트는 172.17.0.1:3306 바인딩 — 앱 컨테이너가 docker bridge 를 통해 접근하고 외부엔 노출 안 함.
if [ "${ENVIRONMENT:-}" = "dev" ] || [ "${ENVIRONMENT:-}" = "staging" ]; then
  # DB 자격증명은 러너 GH secrets 주입 대신 박스에서 SSM 으로 직접 읽는다 (앱 시크릿 SSM 단일화와 같은 결).
  # 컨테이너가 이미 있어 값이 안 쓰이는 배포에서도 pull 은 항상 실행한다 — 박스 재생성 때만 도는 경로로
  # 두면 조용히 썩은 채 가장 필요한 순간(재생성)에 터지므로, 매 배포가 이 경로를 살아있게 검증한다.
  # --network host: IMDSv2 hop limit(기본 1) 탓에 bridge 컨테이너는 인스턴스 role 자격증명을 못 받는다.
  # 이미지 핀은 deploy.yml 의 SSM pull 과 같은 버전을 쓴다.
  AWSCLI_IMAGE="public.ecr.aws/aws-cli/aws-cli:2.35.21"
  ssm_param() {
    docker run --rm --network host "$AWSCLI_IMAGE" ssm get-parameter \
      --name "/piki-core/${ENVIRONMENT}/$1" --with-decryption \
      --query Parameter.Value --output text
  }
  DB_NAME="$(ssm_param db-name)" || { echo "[mysql] SSM db-name 조회 실패 — IAM 권한(app_ssm_read)·파라미터 존재 확인"; exit 1; }
  DB_USERNAME="$(ssm_param db-username)" || { echo "[mysql] SSM db-username 조회 실패"; exit 1; }
  DB_PASSWORD="$(ssm_param db-password)" || { echo "[mysql] SSM db-password 조회 실패"; exit 1; }
  echo "[mysql] DB 자격증명 SSM 로드 완료 (/piki-core/${ENVIRONMENT}/db-*)"

  if docker ps -a --format '{{.Names}}' | grep -qx 'team3-mysql'; then
    echo "[mysql] team3-mysql 이미 존재 — skip"
  else
    echo "[mysql] team3-mysql named 볼륨으로 기동"
    docker run -d \
      --name team3-mysql \
      --restart unless-stopped \
      -p 172.17.0.1:3306:3306 \
      -v team3-mysql-data:/var/lib/mysql \
      -e MYSQL_DATABASE="${DB_NAME}" \
      -e MYSQL_USER="${DB_USERNAME}" \
      -e MYSQL_PASSWORD="${DB_PASSWORD}" \
      -e MYSQL_ROOT_PASSWORD="${DB_PASSWORD}" \
      mysql:8.4
  fi

  # MySQL readiness 대기 — docker run 직후엔 init(첫 기동 시 DB/user 생성 + 재시작) 중이라,
  # 바로 앱이 붙으면 연결/Flyway 가 실패해 헬스체크가 깨진다. 막 떴든 이미 있든(재배포) 앱 기동 전에
  # ping 으로 준비를 확인해 race 를 제거한다. ping 은 서버가 응답하면 성공(인증과 무관).
  echo "[mysql] readiness 대기"
  for i in $(seq 1 30); do
    if docker exec team3-mysql mysqladmin ping -h 127.0.0.1 --silent 2>/dev/null; then
      echo "[mysql] ready (attempt $i)"
      break
    fi
    sleep 2
    [ "$i" -eq 30 ] && { echo "[mysql] readiness timeout (60s)"; exit 1; }
  done
else
  echo "[mysql] prod 환경 — skip (RDS 사용)"
fi

# 4) nginx default 사이트 — 불필요한 stock catch-all 노출. 있으면 제거하고 reload.
if [ -e /etc/nginx/sites-enabled/default ]; then
  echo "[nginx] sites-enabled/default 제거"
  sudo rm -f /etc/nginx/sites-enabled/default
  sudo nginx -t && sudo nginx -s reload
else
  echo "[nginx] default 없음 — skip"
fi

# 4) grafana-alloy — 앱 메트릭 scrape + team3-* 컨테이너 로그를 Grafana Cloud 로 보내는 단일 수집기.
#    Alloy 는 stateless 이고 config 가 레포(infra/alloy/config.alloy)에서 오므로, redis 의 "있으면 skip" 과 달리
#    매 배포마다 config 를 갱신하고 재기동한다 (restart 비용은 작고 scrape 공백도 수초 수준).
#    자격증명(GRAFANA_*)이 없으면(secret 미등록) 기동을 skip 한다 — 빈 endpoint 로 부팅하면 config 검증 실패로
#    crash loop 가 나기 때문. secret 등록 후 다음 배포에 자동 기동된다.
#    --network host: 앱 포트가 127.0.0.1 바인딩(#290)이라 localhost:8080/8081 을 scrape 하려면 필요.
#    --server.http.listen-addr=127.0.0.1: host 네트워크라 debug UI(12345)를 루프백에만 묶어 외부 노출을 막는다.
#    /proc·/sys·/ 마운트: node_exporter(config 의 prometheus.exporter.unix)가 컨테이너 안에서 호스트
#    메모리·swap·디스크를 읽으려면 필요하다. config 의 *_path 가 /host/* 를 가리킨다. ro,rslave 로 읽기전용.
if [ -z "${GRAFANA_METRICS_URL:-}" ]; then
  echo "[alloy] GRAFANA_* 미설정 — skip (secret 등록 후 다음 배포에 기동)"
else
  echo "[alloy] config 갱신 후 (재)기동"
  sudo mkdir -p /etc/alloy-team3
  sudo cp /tmp/team3-alloy/config.alloy /etc/alloy-team3/config.alloy
  docker rm -f team3-alloy 2>/dev/null || true
  docker run -d \
    --name team3-alloy \
    --restart unless-stopped \
    --network host \
    -v /etc/alloy-team3/config.alloy:/etc/alloy/config.alloy:ro \
    -v /var/run/docker.sock:/var/run/docker.sock:ro \
    -v /proc:/host/proc:ro,rslave \
    -v /sys:/host/sys:ro,rslave \
    -v /:/host/root:ro,rslave \
    -e ENVIRONMENT="${ENVIRONMENT:-}" \
    -e GRAFANA_METRICS_URL="${GRAFANA_METRICS_URL:-}" \
    -e GRAFANA_METRICS_USER="${GRAFANA_METRICS_USER:-}" \
    -e GRAFANA_LOGS_URL="${GRAFANA_LOGS_URL:-}" \
    -e GRAFANA_LOGS_USER="${GRAFANA_LOGS_USER:-}" \
    -e GRAFANA_TRACES_URL="${GRAFANA_TRACES_URL:-}" \
    -e GRAFANA_TRACES_USER="${GRAFANA_TRACES_USER:-}" \
    -e GRAFANA_CLOUD_TOKEN="${GRAFANA_CLOUD_TOKEN:-}" \
    -e EXTRACTOR_METRICS_TARGET="${EXTRACTOR_METRICS_TARGET:-}" \
    grafana/alloy:v1.16.1 \
      run --server.http.listen-addr=127.0.0.1:12345 /etc/alloy/config.alloy
fi

echo "런타임 프로비저닝 완료"
