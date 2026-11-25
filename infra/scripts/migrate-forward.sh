#!/usr/bin/env bash
# 컷오버 동안 예전 서버 nginx 가 받은 요청을 새 서버로 넘긴다 (#1314).
#   사용: migrate-forward.sh on|off|revert|check
#   on     새 서버 신뢰 등록 → 예전 nginx 전달 전환 → 예전 IP 경유 응답 확인
#   off    DNS 가 넘어간 뒤 걷어낸다. 예전 nginx 는 원본으로 되돌리고 멈춘다
#   revert 롤백 경계 안에서 끝났을 때 원래대로 되돌린다(재시도, 멱등)
#   check  예전 nginx 가 이미 전달 중이면 실패. 전달 뒤 재실행이 새 DB 를 덮지 않게 막는다
#   env: SRC_APP_HOST·DST_APP_HOST(ssh 별칭) DOMAIN, on 은 NEW_IP·OLD_IP 도. SSH_KEY_FILE 은 없으면 ssh 설정을 따른다
# DNS 캐시·Vercel 의 keep-alive 연결이 예전 IP 로 오는 동안에도 새 서버가 응답하게 한다(3차 이관에서 웹 502 약 16분).
set -euo pipefail

MODE=${1:?on|off|revert|check}
: "${SRC_APP_HOST:?}" "${DOMAIN:?}"

SSHOPT=(-o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=/dev/null)
[ -z "${SSH_KEY_FILE:-}" ] || SSHOPT+=(-i "$SSH_KEY_FILE")
REALIP=/etc/nginx/conf.d/piki-migrate-realip.conf
ORIG=/etc/nginx/sites-available/$DOMAIN
FWD=/etc/nginx/sites-available/$DOMAIN.migrate-forward
ENABLED=/etc/nginx/sites-enabled/$DOMAIN

log() { echo "[migrate-forward] $(date -u +%H:%M:%S) $*"; }
# shellcheck disable=SC2029  # 경로·도메인은 러너에서 펼쳐 보내는 것이 의도다
on_src() { ssh "${SSHOPT[@]}" "ubuntu@$SRC_APP_HOST" "$@"; }
# shellcheck disable=SC2029
on_dst() { ssh "${SSHOPT[@]}" "ubuntu@${DST_APP_HOST:?}" "$@"; }
retry() { local i; for i in 1 2 3; do "$@" && return 0; [ "$i" -lt 3 ] && sleep 5; done; return 1; }

forward_conf() {
  cat <<CONF
server {
    listen 443 ssl http2;
    server_name $DOMAIN;
    ssl_certificate /etc/letsencrypt/live/$DOMAIN/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/$DOMAIN/privkey.pem;
    client_max_body_size 25m;
    location / {
        proxy_pass https://$NEW_IP;
        proxy_ssl_server_name on;
        proxy_ssl_name $DOMAIN;
        proxy_ssl_verify on;
        proxy_ssl_trusted_certificate /etc/ssl/certs/ca-certificates.crt;
        proxy_ssl_verify_depth 2;
        proxy_set_header Host $DOMAIN;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        # SSE 구독이 버퍼링되거나 끊기지 않게 한다.
        proxy_buffering off;
        proxy_read_timeout 1h;
    }
}
server {
    listen 80;
    server_name $DOMAIN;
    return 301 https://\$host\$request_uri;
}
CONF
}

# 새 서버가 예전 서버 IP 를 프록시로 믿어야 레이트리밋·IP 판정이 실제 사용자 IP 로 돈다.
# real_ip_recursive 는 X-Forwarded-For 의 오른쪽부터 믿지 않는 첫 주소를 고른다. 사용자가 헤더를 꾸며도 무시된다.
realip_conf() {
  printf 'set_real_ip_from %s;\nreal_ip_header X-Forwarded-For;\nreal_ip_recursive on;\n' "$OLD_IP"
}

src_original() { on_src "sudo ln -sfn $ORIG $ENABLED && sudo rm -f $FWD && sudo nginx -t -q && sudo systemctl reload nginx"; }
dst_untrust() { on_dst "sudo rm -f $REALIP && sudo nginx -t -q && sudo systemctl reload nginx"; }

# 예전 IP 경유 응답 코드. 예전 앱은 멈춰 있어 200 이면 새 앱이 응답한 것이다. 이 API 는 Redis 도 거친다.
code_via() { curl -s -o /dev/null -w '%{http_code}' --resolve "$DOMAIN:443:$1" "https://$DOMAIN/api/v1/auth/kakao/url" || true; }

forward_on() {
  : "${NEW_IP:?}" "${OLD_IP:?}"
  # 전달 전에 새 서버를 공개 인증서 검증까지 해서 직접 확인한다. DNS 를 바꾼 뒤 처음 드러나는 일이 없게 한다.
  [ "$(code_via "$NEW_IP")" = 200 ] || { log "새 서버 직접 응답이 200 이 아니다 — TLS·앱 확인"; return 1; }

  trap 'log "실패 — 원래 설정으로 되돌림"; revert || true' ERR
  realip_conf | on_dst "sudo tee $REALIP >/dev/null && sudo nginx -t -q && sudo systemctl reload nginx"
  log "새 서버 — 예전 서버 IP($OLD_IP) 신뢰 등록"
  forward_conf | on_src "sudo tee $FWD >/dev/null && sudo ln -sfn $FWD $ENABLED && sudo nginx -t -q && sudo systemctl reload nginx"
  log "예전 서버 — $DOMAIN 요청을 $NEW_IP 로 전달 시작"

  local code=""
  for _ in 1 2 3 4 5 6; do
    code=$(code_via "$OLD_IP")
    [ "$code" = 200 ] && break
    sleep 5
  done
  [ "$code" = 200 ] || { log "예전 IP 경유 응답 $code — 전달 확인 실패"; false; }
  trap - ERR
  log "전달 확인 — 예전 IP 경유 200"
}

revert() {
  local ok=0
  if retry src_original; then log "예전 서버 — 원본 설정"
  else log "예전 서버 원복 실패 — 예전 계정 박스에서 $ENABLED 를 $ORIG 로"; ok=1; fi
  if retry dst_untrust; then log "새 서버 — 예전 IP 신뢰 해제"
  else log "새 서버 신뢰 해제 실패 — 새 박스에서 $REALIP 삭제"; ok=1; fi
  return "$ok"
}

forward_off() {
  # 원본으로 되돌린 뒤 멈춘다. 남은 keep-alive 연결이 끊겨 클라이언트가 DNS 를 다시 조회한다.
  if ! retry on_src "sudo ln -sfn $ORIG $ENABLED && sudo rm -f $FWD && sudo systemctl stop nginx"; then
    log "예전 서버 정리 실패 — 예전 계정 박스에서 nginx 정지 필요"; return 1
  fi
  log "예전 서버 — 전달 해제·nginx 정지"
  retry dst_untrust
  log "새 서버 — 예전 IP 신뢰 해제"
}

check() {
  if [ "$(on_src "readlink $ENABLED")" = "$FWD" ]; then
    log "예전 서버가 이미 새 서버로 전달 중이다 — 컷오버가 끝난 뒤의 재실행이라 새 DB 를 덮을 수 있어 중단"
    return 1
  fi
  log "예전 서버 원본 설정 확인"
}

case "$MODE" in
  on) forward_on ;;
  off) forward_off ;;
  revert) revert ;;
  check) check ;;
  *) echo "usage: $0 on|off|revert|check" >&2; exit 2 ;;
esac
