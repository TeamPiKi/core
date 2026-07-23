# 파싱 outbox 와 claim 소유권 - 설계 결론

> 2026-07-23. "claim 을 extractor 로 이관해야 하는가"를 놓고 벌인 설계 논쟁의 결론 기록.
> 배경: 링크·이미지 파싱 실행은 이미 원격 extractor 로 이관 완료된 상태에서, 남은 claim 머신(dispatch·recover)의 소유권을 검토했다.

## 1. 현 구조 (실측 기준)

### 1.1 구성

- `item_snapshots` 가 도메인 상태머신과 작업 큐를 겸한다. 별도 outbox 테이블 없음.
- claim 머신은 core 소유: `ItemParsingScheduler` 의 dispatch(1s 주기, PENDING 을 FOR UPDATE claim)와 recover(15s 주기, 60s stale PROCESSING 재실행·종결).
- 실행은 전부 원격: 링크는 `/extractions`, 이미지는 `/internal/extractions/image` (download, OCR, crop, 결과 업로드까지 extractor 가 수행). core 워커는 얇은 HTTP 호출자 + 상태 전이자다.
- extractor 는 무상태 HTTP 서비스: DB 의존 0, 도메인 자격증명 0, staging·prod 가 박스 1대 공유.

### 1.2 상태는 클라이언트 계약이다

- PENDING·PROCESSING·READY·FAILED 네 상태 전부 wish 응답의 `status` 로 노출된다.
- UX 소비는 3그룹: "담는 중"(PENDING+PROCESSING) / "완성"(READY) / "실패"(FAILED). READY·FAILED 는 SSE 알림으로도 전달.
- 즉 파싱의 실행 진행 상태 자체가 사용자 대면 도메인 사실이다. 이 요구가 이후 모든 판단의 축이 된다.

### 1.3 보장

- execution at-least-once: 등록 트랜잭션이 PENDING 커밋 = 의도의 영속화. 인메모리 큐 유실과 무관하게 반드시 한 번은 claim 된다.
- 일시 오류(네트워크·timeout·5xx)는 FAILED 로 종결하지 않고 PROCESSING 유지, recover 가 60s stale 후 재실행. 확정 실패(422 번역)는 즉시 종결.
- 상한 2회, 최악 약 150s. 재시도 가치 판정(어떤 실패가 일시인가)은 extractor 의 422 계약이 쥔다.

## 2. 패턴 분류 - outbox 가 아니라 상태 기반 조정 루프

### 2.1 패밀리 수준에서는 outbox 와 동족

- "의도를 도메인 트랜잭션과 함께 영속화하고, 별도 프로세스가 최소 1회 확실히 처리한다"는 보장 구조는 outbox 와 동일하다.

### 2.2 종(種) 수준에서는 다르다

- outbox 의 존재 이유는 서로 다른 두 사실(도메인 write + 외부로 나갈 발행물)의 원자적 묶음, 즉 dual-write 해소다.
- 여기는 사실 하나(PENDING snapshot)가 도메인 상태와 작업 정의를 겸한다. 발행물도, 두 번째 시스템도 없어 묶을 것 자체가 없다.
- 더 정확한 멘탈 모델: 선언된 상태(PENDING)를 폴러가 계속 수렴시키는 조정 루프(reconciliation loop). k8s 컨트롤러 류.

### 2.3 왜 이 구분이 실무에 중요한가

- "outbox 다"로 분류하면 별도 테이블이 정석처럼 보이고, "도메인 상태머신이다"로 분류하면 융합이 정석이다.
- 1.2 의 제품 요구(실행 진행 = 사용자 정보)가 있는 한, 융합은 지름길이 아니라 그 요구의 가장 직접적인 구현이다. 워커의 전이가 곧 도메인 write 라 추가 배선 없이 사용자에게 보인다.

## 3. claim 을 extractor 로 옮기는 안 - 장단

### 3.1 얻는 것

- **backpressure 자기조절**: ext 가 여유 있을 때만 집어가므로, ext 수용량 지식을 core 워커 풀·timeout 으로 이중 표현하는 부담이 사라진다.
- **호출-완료 모호성 소멸**: "HTTP read timeout 은 났는데 작업은 성공"으로 생기는 중복 작업 창이 구조적으로 없어진다.
- **재시도 지식 동거**: 재시도 가치 분류(422)는 이미 ext 에 있으므로, 시도 횟수까지 오면 재시도 로직이 실패를 제일 잘 아는 곳에 모인다.
- **수평 확장**: ext N 대의 작업 분배가 claim 경쟁으로 자연 해결된다.

### 3.2 치르는 것

- **옮길 수 없는 절반**: 상태 전이는 SSE 의 발화점이자 도메인 write 이자 "ext 가 죽어도 N분 내 종결"의 생존 보증이라 core 에만 있을 수 있다. 결과는 완전 이관이 아니라 재시도 의미론의 분할(ext=시도, core=마감·전이)이다.
- **ext 의 첫 DB 의존**: 드라이버·풀·자격증명·SG 경로·스키마 지식이 의존 0 인 서비스에 한꺼번에 생긴다.
- **최소 권한 후퇴**: 적대적 HTML 과 LLM 출력을 다루는 가장 노출된 서비스에 도메인 DB 자격증명이 간다. staging·prod 공유 박스 1대에 두 환경의 자격증명이 앉는다.
- **스키마의 계약 승격**: `item_snapshots` 는 활발히 진화하는 테이블(버저닝 에픽 6단계)인데, writer 가 둘이면 모든 마이그레이션이 두 repo 배포 순서 조율거리가 된다.
- **재건축 리스크**: 실전 버그를 거치며 다듬어진 at-least-once 머신을 다른 언어·repo 에 다시 짓는 회귀 비용.

### 3.3 별도 outbox 테이블 안의 SSOT 검토

상태가 사용자 계약(1.2)이므로, 테이블을 나누면 갈래가 셋뿐이다.

- **갈래 1 - 도메인 4상태 유지 + 잡 테이블에서 투영**: 모든 전이가 두 번 쓰인다(잡 행 + 도메인 투영). 같은 진실이 두 행에 살고, 투영 규율(단방향·멱등·도메인 터미널 우선)로 모순은 막아도 이중화와 지연은 실재한다.
- **갈래 2 - 진실 재분할** (도메인은 UX 입도 3상태, 기계 사실은 잡 테이블): SSOT 는 깨끗해지지만 클라가 파싱하는 status enum 을 바꾸는 와이어 계약 변경이다.
- **갈래 3 - lease 테이블 + PROCESSING 파생**: 잡 테이블을 상태 복제본이 아니라 임차 기록(snapshot_id·claimed_by·lease_until·attempt)으로 좁히고, PROCESSING 은 "PENDING 인데 lease 활성"으로 응답에서 파생한다. 이중 write 없음, 계약 변경 없음. 분리를 한다면 유일하게 건전한 형태.

## 4. 결론

### 4.1 판정

- **지금은 옮기지 않는다.** 편익(3.1)은 부하·인스턴스 수에 비례하는 미래형인데 현재는 ext 1대·낮은 처리량이고, 비용(3.2)은 옮기는 날 전액 선불이다.
- 지금도 조악한 backpressure 는 존재한다: 과부하 실패는 일을 잃지 않고 PROCESSING 유지 후 60s+ 간격 재시도로 지연으로 변환된다.
- **미래에도 정답은 "이관"이 아니라 "분배 계층 삽입"이다.** 전이·SSE·백스톱이 core 에 남는 한 공유 표면은 좁을수록 좋다. ext 가 core 의 도메인 행을 직접 claim 하는 형태는 현재에도 미래에도 열등하다.
- 형태 우선순위: SQS 류 큐 > lease 테이블(새 인프라 회피 시) > 도메인 행 직접 claim(항상 열등).

### 4.2 큐를 끼워도 사라지지 않는 것 (dual-write)

- DB 커밋과 큐 publish 는 원자적으로 못 묶는다. 커밋 후 publish 전에 죽으면 영구 미아가 생기므로, PENDING 을 폴링해 publish 하는 relay 는 core 에 남는다.
- 보장의 3층 분업: 작업의 존재 = DB(PENDING 행), 전달 = 큐(visibility timeout·redrive), 사실상 1회 = core 의 멱등 전이.
- 현 스케줄러는 손으로 짠 SQS 다: claim = receive, stale 60s 윈도 = visibility timeout, 상한 2회 = maxReceiveCount 후 DLQ, 멀티 인스턴스 SKIP LOCKED = 큐에선 불필요. 큐 삽입은 이 수제 머신을 통째로 지우는 선택이고, 직접 claim 이관은 같은 머신을 ext 에 재건축하는 선택이다.

### 4.3 전환 신호 (하나라도 실측되면 재론이 아니라 실행)

- ext 를 처리량 때문에(이중화가 아니라) 2대 이상으로 늘려야 할 때.
- core 의 워커 풀·timeout 튜닝이 실제 장애·운영 고통으로 관측될 때.
- claim 의 FOR UPDATE 와 위시 조회의 락 경합이 메트릭에 잡힐 때.
- ext 에 별도 담당자가 생겨 repo 경계가 팀 경계가 될 때.

### 4.4 역할 규정

- **extractor 는 "추출을 담당하는 도메인"이 아니라 "신청받은 추출을 수행하는 엔진"이다.**
- 분업: core = 무엇을·언제·몇 번 (등록·생애주기·재시도 예산·상태·사용자 노출) / ext = 어떻게 (파서 선택·LLM·헤드리스 라우팅·몰 지식). 422 계약은 "어떻게"의 전문성이 "몇 번"의 정책에 보고를 올리는 경계다.
- 데이터는 계산하는 쪽이 아니라 조인하는 쪽에 산다: snapshot 은 위시·토너먼트·가격 이력이 끊임없이 조인하므로 core 소유가 맞다. 게이트웨이로 ext 직접 호출(조회까지 ext)은 이 원칙과 인증·최소권한을 모두 깨는 비정석.
- backpressure 만 필요해지면 소유권 이동 없이 살 수 있다: ext 의 429 + Retry-After 와 core 디스패처의 적응적 동시 실행 창.

## 부록 - 관련 코드와 발견

- 관련 코드: `ItemParsingScheduler`(dispatch·recover), `AsyncItemParsingWorker`·`AsyncImageParsingWorker`(단건 시도·전이), `HttpImageSnapshotExtractor`(이미지 원격 위임), `ItemStatus`(상태 계약), `ItemParsingService.retryOrFailStaleProcessing`(재실행·종결 판정).
- 주석 드리프트 발견: `ItemStatus.PENDING` 의 "이미지 등록 경로는 PENDING 을 거치지 않고 곧장 PROCESSING" 은 이미지 durable 화 이전 서술로, `WishlistApiExamples` 의 "이미지도 outbox 적재 PENDING" 과 어긋난다. 별도 정리 후보.
