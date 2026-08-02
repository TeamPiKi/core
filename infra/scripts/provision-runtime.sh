#!/usr/bin/env bash
# EC2 런타임 프로비저닝 — 멱등(idempotent). 배포 때 실행되어 박스 안 런타임 설정
# (swap / redis / nginx default / grafana alloy)이 레포 정의 상태가 되도록 보장한다.
# swap·redis·nginx 는 이미 있으면 skip 하고, alloy 는 공용 블록(TeamPiKi/infra)이 매 배포 갱신·재기동한다. (#217, #743)
#
# docker 명령은 sudo 없이(ubuntu 가 docker 그룹), 시스템·nginx 는 sudo 로 — deploy.yml 기존 패턴과 동일.
set -euo pipefail

# dockerized aws-cli — mysql(3절)·alloy(4절)의 SSM pull 이 공유한다. 박스엔 aws cli 가 없다.
# 이미지 핀은 deploy.yml 의 SSM pull 과 같은 버전을 쓴다.
AWSCLI_IMAGE="public.ecr.aws/aws-cli/aws-cli:2.35.21"

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

# 3) mysql (dev 전용) — prod 는 RDS 를 쓰므로 dev 일 때만 로컬 컨테이너로 기동.
#    redis 와 동일하게 named 볼륨 + 있으면 skip 패턴. 초기 자격증명은 앱이 쓰는 것과 같은 SSM 값에서 읽는다.
#    포트는 172.17.0.1:3306 바인딩 — 앱 컨테이너가 docker bridge 를 통해 접근하고 외부엔 노출 안 함.
if [ "${ENVIRONMENT:-}" = "dev" ]; then
  # DB 자격증명은 러너 GH secrets 주입 대신 박스에서 SSM 으로 직접 읽는다 (앱 시크릿 SSM 단일화와 같은 결).
  # 컨테이너가 이미 있어 값이 안 쓰이는 배포에서도 pull 은 항상 실행한다 — 박스 재생성 때만 도는 경로로
  # 두면 조용히 썩은 채 가장 필요한 순간(재생성)에 터지므로, 매 배포가 이 경로를 살아있게 검증한다.
  # --network host: IMDSv2 hop limit(기본 1) 탓에 bridge 컨테이너는 인스턴스 role 자격증명을 못 받는다.
  # --region 명시: docker run 은 호스트 리전 설정을 상속하지 않는다 (deploy.yml 의 SSM pull 과 동일 고정).
  ssm_param() {
    docker run --rm --network host "$AWSCLI_IMAGE" ssm get-parameter \
      --name "/piki-core/${ENVIRONMENT}/$1" --with-decryption \
      --region ap-northeast-2 --query Parameter.Value --output text
  }
  DB_NAME="$(ssm_param db-name)" || { echo "[mysql] SSM db-name 조회 실패 — IAM 권한(app_ssm_read)·파라미터 존재 확인"; exit 1; }
  DB_USERNAME="$(ssm_param db-username)" || { echo "[mysql] SSM db-username 조회 실패"; exit 1; }
  DB_PASSWORD="$(ssm_param db-password)" || { echo "[mysql] SSM db-password 조회 실패"; exit 1; }
  echo "[mysql] DB 자격증명 SSM 로드 완료 (/piki-core/${ENVIRONMENT}/db-*)"

  # 주의: 이 자격증명은 사실상 불변이다. 값은 컨테이너 "최초 생성" 시에만 쓰이므로, SSM 값만 바꾸면
  # 기존 MySQL 은 옛 비밀번호로 남고 앱만 새 값으로 붙어 인증이 깨진다. 회전이 필요하면 MySQL 쪽
  # ALTER USER 와 SSM 갱신을 같은 절차로 묶어 수동 수행한다 (GH secrets 시절부터 동일한 제약).
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

# 4) grafana-alloy — 관측 수집기. config·기동 블록의 SSOT 는 TeamPiKi/infra 공용 블록(blocks/alloy)이고(#743),
#    deploy.yml 의 'Upload deploy files' 가 /tmp/piki-deploy/alloy/ 로 올려둔다. 여기는 core 박스 값
#    (--environment/--box)으로 호출만 한다. skip 가드(GRAFANA_METRICS_URL 빈 값 시 exit 0)·기동 전
#    validate 게이트·--network host·호스트 마운트·Running 확인은 전부 블록이 책임진다.
#    자격증명(GRAFANA_*)은 SSM 공유 경로(/piki/observability/grafana-*)에서 박스가 직접 읽는다(#771) —
#    세 서비스 박스가 같은 경로를 읽어 토큰 회전이 1곳 put-parameter 로 끝난다. GH secrets 경유 폐기.
#    필수 5종(metrics·logs URL/USER, token) 실패는 즉시 중단, traces 2종은 빈 값 허용(블록이 더미로 무해 처리).
obs_param() {
  docker run --rm --network host "$AWSCLI_IMAGE" ssm get-parameter     --name "/piki/observability/$1" --with-decryption     --region ap-northeast-2 --query Parameter.Value --output text
}
GRAFANA_METRICS_URL="$(obs_param grafana-metrics-url)" || { echo "[alloy] SSM grafana-metrics-url 조회 실패 — IAM(app_ssm_read)·파라미터 존재 확인"; exit 1; }
GRAFANA_METRICS_USER="$(obs_param grafana-metrics-user)" || { echo "[alloy] SSM grafana-metrics-user 조회 실패"; exit 1; }
GRAFANA_LOGS_URL="$(obs_param grafana-logs-url)" || { echo "[alloy] SSM grafana-logs-url 조회 실패"; exit 1; }
GRAFANA_LOGS_USER="$(obs_param grafana-logs-user)" || { echo "[alloy] SSM grafana-logs-user 조회 실패"; exit 1; }
GRAFANA_CLOUD_TOKEN="$(obs_param grafana-cloud-token)" || { echo "[alloy] SSM grafana-cloud-token 조회 실패"; exit 1; }
GRAFANA_TRACES_URL="$(obs_param grafana-traces-url)" || GRAFANA_TRACES_URL=""
GRAFANA_TRACES_USER="$(obs_param grafana-traces-user)" || GRAFANA_TRACES_USER=""
export GRAFANA_METRICS_URL GRAFANA_METRICS_USER GRAFANA_LOGS_URL GRAFANA_LOGS_USER
export GRAFANA_TRACES_URL GRAFANA_TRACES_USER GRAFANA_CLOUD_TOKEN
echo "[alloy] Grafana 자격 SSM 로드 완료 (/piki/observability/grafana-*)"
#    수집 대상은 컨테이너 label opt-in(piki.observe 등, contracts/observability.md) — 서비스 열거 regex 와
#    cross-box scrape(EXTRACTOR_METRICS_TARGET)는 폐기됐다(extractor prod 박스는 자체 Alloy 가 수집).
# 전환기 잔재 정리: 구 수집기(team3-alloy)·구 config 경로가 남으면 새 수집기(piki-alloy, 블록이 기동)와
# 이중 수집된다. 없으면 no-op 라 유지 비용이 없고, 전 환경 개편 배포가 한 바퀴 돈 뒤 제거 가능.
# 제거 실패는 조용히 넘기지 않는다 - 새 수집기가 다른 이름이라 docker run 의 이름 충돌 안전망이 없어,
# rm 이 조용히 실패하면 두 수집기가 같은 신호를 이중 전송하는 상태가 소리 없이 성립하기 때문.
if docker inspect team3-alloy >/dev/null 2>&1; then
  timeout 30 docker rm -f team3-alloy \
    || { echo "[alloy] 구 수집기(team3-alloy) 제거 실패 - 이중 수집 방지를 위해 중단"; exit 1; }
fi
sudo rm -rf /etc/alloy-team3
bash /tmp/piki-deploy/alloy/provision-alloy.sh \
  --config /tmp/piki-deploy/alloy/config.alloy \
  --name piki-alloy \
  --environment "${ENVIRONMENT:?ENVIRONMENT 미주입 — deploy.yml envs 확인}" \
  --box piki-core

echo "런타임 프로비저닝 완료"
