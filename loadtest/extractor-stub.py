#!/usr/bin/env python3
"""extractor stub — dev 부하테스트(#911) 전용.

실제 extractor 는 prod 와 박스를 공유하므로 부하테스트의 파싱 호출이 그리로 가면 안 된다.
GH loadtest environment 의 EXTRACTOR_PROD_ADDRESS 를 이 stub(부하 DB 박스 사설 IP:8090)으로
override 해, dev 배포만 여기를 보게 한다.

계약 정본은 extractor repo docs/api-contract.md — 성공 200 의 필드 모양만 흉내 낸다.
  GET  /actuator/health            -> 200 {"status":"UP"}  (deploy.yml 배포 가드 통과용)
  POST /internal/extractions/link  -> 지연 후 200 (name·imageUrl·currentPrice non-null 보장)
  POST /internal/extractions/image -> 지연 후 200 (finalUrl null, method LLM)

지연은 STUB_DELAY_MIN_MS/STUB_DELAY_MAX_MS(기본 1000~4000ms) 균등 분포 — 파싱 워커
(itemParsingExecutor, 동시 8)가 실제와 비슷한 시간 동안 슬롯을 점유하게 한다.
표준 라이브러리만 사용한다 (python:3.12-alpine 에서 파일 하나로 기동).
"""
import hashlib
import json
import os
import random
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DELAY_MIN_MS = int(os.environ.get("STUB_DELAY_MIN_MS", "1000"))
DELAY_MAX_MS = int(os.environ.get("STUB_DELAY_MAX_MS", "4000"))


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    # 요청 로깅을 켠다 — 연결 시도 1(2026-08-11)에서 무로그 탓에 "stub 이 요청을 받긴 했는가"조차
    # 판별 불가였다. 한 줄/요청이라 부하(등록 5/s = 10분에 3천 줄)에도 디스크 부담이 없다.
    def log_message(self, fmt, *args):
        sys.stderr.write(f"{time.strftime('%H:%M:%S')} {self.client_address[0]} {fmt % args}\n")

    def _json(self, status, obj):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.startswith("/actuator/health"):
            self._json(200, {"status": "UP"})
        else:
            self._json(404, {})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length) if length else b"{}"
        try:
            req = json.loads(raw or b"{}")
        except json.JSONDecodeError:
            req = {}

        time.sleep(random.uniform(DELAY_MIN_MS, DELAY_MAX_MS) / 1000.0)

        if self.path == "/internal/extractions/link":
            url = req.get("url") or "https://example.com/loadtest"
            h = hashlib.sha1(url.encode()).hexdigest()[:8]
            self._json(200, {
                "name": f"부하테스트 상품 {h}",
                "imageUrl": f"https://example.com/loadtest/{h}.png",
                "currentPrice": 10000 + int(h, 16) % 90000,
                "currency": "KRW",
                "finalUrl": url,
                "method": "STRUCTURED",
            })
        elif self.path == "/internal/extractions/image":
            self._json(200, {
                "name": "부하테스트 이미지 상품",
                "imageUrl": "https://example.com/loadtest/image.png",
                "currentPrice": 25000,
                "currency": "KRW",
                "finalUrl": None,
                "method": "LLM",
            })
        else:
            self._json(404, {})


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8090), Handler).serve_forever()
