# Grafana 대시보드

`dashboard.json` 은 PIKI 앱 개요 대시보드다 — 주요 메트릭과 앱 로그를 한 화면에 모은다.
Grafana Cloud 가 호스팅하는 Grafana 이므로 셀프호스팅 provisioning 이 아니라 **수동 import** 한다.

## Import 방법

1. Grafana Cloud(`piki.grafana.net`) → **Dashboards → New → Import**
2. `dashboard.json` 내용을 붙여넣기(또는 파일 업로드)
3. 데이터소스 매핑:
   - **DS_PROM** → `grafanacloud-piki-prom` (Prometheus)
   - **DS_LOKI** → `grafanacloud-piki-logs` (Loki)
   - **DS_TEMPO** → `grafanacloud-piki-traces` (Tempo)
4. **Import**
5. 상단 **환경** 드롭다운에서 `dev` / `prod` 를 골라 본다(기본 `prod`).

## 패널

증상 → 원인 → 로그 순서로 배치한다(위에서 아래로):

1. **한눈 건강(stat, 임계 색상)** — 앱 up · 5xx 에러율 · p99 지연 · JVM heap 사용률 · 호스트 메모리 사용률 · swap 사용량. 위험하면 노랑/빨강.
2. **트래픽(RED)** — 요청률(status별) · 5xx 요청률(uri별) · 지연 p50/95/99.
3. **JVM 메모리·GC** — heap used/max · nonheap · GC pause p99·빈도. (heap 256m·906Mi 박스라 1순위 리스크)
4. **호스트(node_exporter)** — 메모리 used/total · swap · 디스크 여유. OOM-kill·swap 폭증을 OS 레벨로 잡는다.
5. **의존성** — 502율(Gemini 등 외부 실패) · executor active/queued · HikariCP active/idle/pending.
6. **리소스** — 앱 process/system CPU · JVM 스레드 · 호스트 CPU.
7. **로그** — 에러/경고 필터 로그 + 전체 로그(`piki-core-blue`/`piki-core-green`, 전환기엔 옛 `team3-*` 도 양쪽 매칭).
8. **파싱·추출 관측** — **파싱 실패율**(`item.parsing` 메트릭 rate 기반 레드 스위치, 임계 색상, 환경 따라감) + **파싱 결과(result별)·추출 방법(via별)**(`item.parse.result`·`extract via=` 로그를 `count_over_time | logfmt` 로 셈) + **파싱 이벤트 로그**(어느 URL 이 왜 실패했는지 맥락). 단건 파이프라인 드릴다운은 상단 트레이스의 **`아이템` 탭**(`name = "item.parse"`)으로 본다(#506).

   - **누적 카운트 메트릭이 아니라 로그/트레이스 기반인 이유**: 인메모리 카운터는 앱 재배포마다 0으로 리셋된다. dev 는 하루 수십 번 배포해 누계가 무의미하고, `increase` 창집계도 잦은 재시작+blue/green instance churn 에 시리즈를 떨군다. 로그 이벤트는 Loki 에 durable 하게 쌓여 배포에 영향받지 않으므로 결과·방법 집계의 정확한 소스다. 메트릭은 "정확한 누계"가 아니라 "실패율 급등 감지(레드 스위치)" 한 가지에만 쓴다 — 그 역할엔 짧은 창 rate 라 리셋이 무관하다.

앱 메트릭은 `application="piki-core"` 라벨로 필터한다(#727 rename, 전환기 쿼리는 `PIKI|piki-core` 양쪽 매칭 — 구 시계열 stale 후 단독 정리).
blue-green 이라 한 시점에 한 슬롯만 활성이며 `instance` 라벨로 blue/green 을 구분한다.
호스트 메트릭(`node_*`)은 application 라벨이 없다 — `prometheus.exporter.unix` 가 별도로 내보낸다.

### 환경 구분 (dev / prod)

dev·prod EC2 가 **같은 Grafana Cloud** 로 push 하므로, 상단 **환경** 변수(`$environment`)로 시계열을 가른다.
이 라벨이 없으면 `instance`(piki-core-blue/green)가 양쪽 EC2 에서 같아 dev·prod 가 한 시계열로 섞인다.

- **메트릭** — Alloy remote_write 의 `external_labels` 가 `environment` 라벨을 붙인다(앱·호스트 메트릭 모두). 모든 패널 쿼리가 `environment="$environment"` 로 필터한다.
- **로그** — Loki `static_labels` 가 `environment` 를 붙인다. 로그 패널 stream selector 가 같은 라벨로 필터한다.
- **트레이스** — Alloy `otelcol.processor.transform` 이 OTel 표준 `deployment.environment` 를 붙인다. trace 필터가 `resource.deployment.environment="$environment"` 로 거른다.

`environment` 변수는 `label_values(environment)` 로 실제 존재하는 값만 드롭다운에 띄운다(dev push 가 없으면 prod 만 보인다).

### 알려진 한계 (계측이 더 필요한 것)
- **Gemini(LLM) 호출 자체**는 직접 계측이 없어 `502` 서버 응답으로만 간접 관측된다. 호출 latency/실패율을 직접 보려면 `GeminiHttpClient` 의 RestClient 를 자동설정 builder/`@Observed` 로 바꿔야 한다.
- **S3·Redis** 호출도 자동 계측이 없다(필요 시 `@Timed`/MeterRegistry 추가).

## 갱신

GC 콘솔에서 대시보드를 수정한 뒤 **Dashboard settings → JSON Model** 을 복사해 이 파일을 덮어쓰면
변경이 버전관리된다. (반대로 이 파일을 고쳐 다시 import 해도 된다.)
