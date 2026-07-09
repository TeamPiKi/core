# 테스트 컨벤션

이 파일은 `CLAUDE.md` 가 `@.claude/rules/testing-convention.md` 로 import 한다 (자동 로드). 프로젝트의 모든 테스트 규약을 담는다.

## 테스트 분류

테스트는 **세 종류**를 기본으로 둔다 — 단위 · 통합 · E2E. 두 축으로 갈린다: **무엇을 검증하나**(분기 망라 vs 시나리오·contract), **외부 의존성을 실제로 타나**(stub 격리 vs 실제 호출). 여기에 특정 조건에서만 쓰는 **직렬화/호환성 테스트**가 더해진다.

- **단위 테스트** — Spring · DB 없이 도메인 객체와 순수 함수만 검증. 컨텍스트가 뜨지 않으므로 가장 빠르다. 입력 형식 / 정규화 / 상태 변화 / 계산 등 분기 폭이 큰 영역은 모두 여기서 망라한다.
- **통합 테스트** — 컨트롤러(HTTP 진입)부터 DB(Testcontainers MySQL)까지 실제 흐름을 검증. 외부 호출(Gemini API, HTTP fetch 등)은 **stub 빈으로 격리**한다 — 외부를 실제로 타지 않는다.
- **E2E 테스트** — 외부 의존성(외부 쇼핑몰 HTTP, Gemini API 등)을 **stub 없이 실제로 호출**해, stub 으로는 검증 불가능한 "실제 외부와 우리 코드의 접점"(플랫폼별 파서 / LLM / 차단 커버리지 등)을 확인한다. 네트워크 비결정성·외부 차단 탓에 CI 기본 실행에서 제외하고 환경 의존·`@Disabled` 로 격리한다. (아래 `### E2E 테스트`)
- **직렬화/호환성 테스트 (조건부)** — Redis·캐시에 **객체를 직렬화**(JSON·Java serialization)해 저장하는 경우에 한해 둔다. 현재 코드는 `StringRedisTemplate` 로 문자열만 저장해 이 시점 기준 대상이 없지만, 이는 고정된 사실이 아니다 — Bucket4j 버킷 상태 등 객체 직렬화를 도입하는 PR 이 나오면 그 PR 이 이 테스트를 함께 추가한다. (아래 `### 직렬화 / 호환성 테스트`)

서비스 단독 테스트는 두지 않는다. 분기는 도메인으로 옮겨 단위 테스트로 내리고, 시나리오·contract 검증은 통합 테스트로 흡수한다. (서비스에 분기가 쌓이면 도메인 모델이 빈약하다는 신호다 — 아래 결정 트리.)

### 예외적 분류
시간 · 동시성 · 트랜잭션 경계 검증처럼 컨트롤러 진입으로 표현이 어려운 경우, 별도 분류를 명시적으로 만든 뒤 추가한다(`*ConcurrencyIntegrationTest` / `*TimingIntegrationTest`, 아래 `### 동시성 · 시간 의존 통합 테스트`). 기본 분류에 욱여넣지 않는다.

### 테스트 가치 판단 (무엇을 테스트하는가)

분류·결정 트리는 "어디서 테스트하나"를 답한다. 그 앞에 "이걸 테스트할 가치가 있나"를 먼저 판단한다. 모든 코드에 테스트가 필요한 건 아니다 — 핵심 20% 에 집중해 신뢰의 80% 를 얻는다.

**기준 한 줄: 커버리지 숫자가 아니라 "깨지면 비싼가 + 회귀 위험이 큰가 + 읽기·변경이 잦은가".**

| 가치 | 무엇 | 어디서 |
|---|---|---|
| 높음 (반드시) | 도메인 정책·계산·상태 전이 (분기 큰 것) | 단위 |
| 높음 (반드시) | 핵심 유저 여정 (위시 등록·토너먼트 진행/완료·인증) | 통합 |
| 높음 (반드시) | HTTP 계약 (응답 모양·예외→상태 매핑) | 통합 |
| 높음 (반드시) | 외부 의존성 분기 (성공/실패/timeout) | stub 통합 |
| 낮음 (생략·수동) | 단순 위임·getter·자명한 매핑, 프레임워크가 보장하는 것 (JPA 기본 CRUD) | — |
| 낮음 (생략·수동) | 수명 짧은 이벤트성 기능 | 수동 테스트로 대체 |

가치가 낮다고 판단해 생략하면, 그 판단(왜 안 하나)을 PR·코드에 한 줄 남긴다. "빠뜨린 것"과 "의도적으로 안 한 것"을 구분하기 위해서다.

### 분기 위치 결정 트리

```text
이 분기는 무엇을 검사하는가?

(1) 입력 형식 · 값 범위 · null · 정규화
    → 도메인 객체 생성자 / 팩토리 안 → 단위 테스트
(2) 상태 변화 · 계산 · 정책
    → 도메인 객체 메서드 안 → 단위 테스트
(3) DTO ↔ 도메인 매핑 · 정규화
    → 매퍼 함수 → 단위 테스트
(4) "DB 상태 + 결정" (예: "이미 존재하는가?")
    → 통합 테스트
(5) HTTP contract (Bean Validation · 예외→상태 매핑 · 응답 모양)
    → 통합 테스트
(6) 외부 API 호출 결과 분기 (성공 / 실패 / timeout)
    → 외부 호출 stub + 통합 테스트
(7) 실제 외부 의존성과의 접점 (어느 플랫폼이 파서/LLM/차단인가 등)
    → stub 없이 실제 호출 → E2E 테스트 (환경 격리 · CI 제외)
(8) Redis · 캐시에 **객체를 직렬화**해 저장하는 경우의 배포 간 호환성 (객체 직렬화 저장 시. 현재는 문자열만 저장)
    → 직렬화 스냅샷 비교 → 직렬화/호환성 테스트
```

서비스에 분기가 쌓이면 보통 **도메인 모델이 빈약(anemic)** 하다는 신호다. 분기를 도메인 객체 메서드로 옮긴 뒤 단위 테스트로 검증한다.

### 단위 테스트
- 위치: 도메인 객체와 같은 패키지 (예: `src/test/.../product/domain/ProductLinkTest.kt`).
- Spring · DB 의존성 없이 작성. 부팅 비용 0.
- 분기 망라가 목적. `@ParameterizedTest` 적극 활용 권장.

### 통합 테스트 작성 원칙
- 엔드포인트 당 시나리오·contract 검증을 3~5건 수준으로 유지한다. 분기 망라 목적의 추가는 도메인 단위로 내린다.
- 응답 contract(`status`, `code`, `detail`, `data` 필드 모양)를 단언에 포함한다. 도메인 객체 단언만으로는 직렬화·예외 매핑 회귀를 잡지 못한다.
- 검증 실패(400) · 비즈니스 예외(409 등) 케이스도 contract 검증에 포함한다.
- DB 는 Testcontainers MySQL 한정 (H2 등 다른 DB 로 대체 금지).

### E2E 테스트
- **목적**: 외부 의존성과 우리 코드의 실제 접점을 검증한다. 통합 테스트가 stub 으로 격리하는 바로 그 경계(외부 쇼핑몰 HTTP, Gemini API 등)를 **실제로 호출**해, stub 가정과 현실의 어긋남·플랫폼별 커버리지(파서 / LLM / 차단)를 확인한다.
- **격리 (필수)**: 둘 중 하나를 **반드시** 둔다 — 환경변수 기반 `@EnabledIfEnvironmentVariable(named = "...", matches = ".+")`(환경이 있으면 실행) 또는 `@Disabled("이유 명시")`(영구 비활성, 수동 실행 시 주석 해제). **둘 다 없는 무방비 E2E 는 금지** — CI 에서 실제 외부 호출이 돌아 flaky·비용·외부 차단을 유발한다.
- **CI 제외**: 네트워크 비결정성·외부 차단으로 flaky 하므로 CI 기본 실행에 넣지 않는다. 환경변수가 세팅된 로컬·주기 잡에서만 돈다.
- **컨텍스트 분리**: `IntegrationTestSupport` 를 상속하지 않는다 — stub 컨텍스트가 실제 호출을 가린다. 외부 호출만 필요하면 Spring 없이 standalone 으로 둔다.
- **네이밍**: `*E2ETest.kt`. 비결정성을 통합·단위 분류로 전파시키지 않는다.

### 직렬화 / 호환성 테스트
- **적용 조건**: Redis·캐시에 **객체를 직렬화**(JSON·Java serialization)해 저장할 때만 작성한다. 현재 코드는 `StringRedisTemplate` 로 문자열만 저장해 이 시점 기준 대상이 없으나, 이는 지금 상태일 뿐 고정된 사실이 아니다 — 객체 저장을 도입하는 PR 이 나오면 그 PR 이 이 테스트를 함께 추가한다.
- **동기**: blue-green 배포 중 구버전·신버전 인스턴스가 같은 Redis(또는 DB JSON 컬럼)를 잠시 공유한다. 한쪽이 쓴 직렬화 객체를 다른 쪽이 못 읽으면 런타임 장애다. 객체 필드를 바꾸면 이 호환성이 컴파일·일반 테스트로는 안 드러난 채 조용히 깨진다.
- **전략 (Approval Test 식)**: Redis·캐시에 직렬화되어 저장되는 객체는 직렬화 스냅샷(고정 JSON 등)을 리소스로 두고, 현재 직렬화 결과가 그 스냅샷과 호환되는지 검증한다. 스냅샷이 깨지면 테스트 실패가 "캐시 버저닝(키에 버전 부여) · 마이그레이션 · 하위호환 필드 처리" 가 필요하다는 신호를 준다.
- **하위호환 규칙**: 신규 필드는 nullable · default 로 더해 구버전이 신버전 데이터를 읽어도 깨지지 않게 한다. 필드 제거·타입 변경은 단계 배포(add → 양쪽 호환 → remove)로 나눈다.
- **대상**: Redis 저장 객체(인증 토큰·세션 상태 등), 애플리케이션 캐시 직렬화 대상.
- **분류·네이밍**: Spring 없이 도는 단위 테스트의 일종이나 목적이 뚜렷해 분리한다. `*SerializationCompatibilityTest.kt`.

### 테스트 실행 전 사전 검증

**테스트는 항상 단위 + 통합을 함께 돌린다.** 단위 테스트만 따로 분리해 돌리지 않는다. 분기 망라는 단위 테스트로 작성하지만, 실행 시점에는 통합까지 함께 돌려 컨트롤러·DB 회귀를 잡는다.

**테스트 실행 전 Docker 데몬이 떠 있는지 먼저 검증한다.** Testcontainers 가 Docker 를 요구한다. 사전 검증 없이 그냥 돌리면 "Gradle 부팅 → 컴파일 → 컨테이너 시도 → 실패 → Docker 켬 → 재실행" 으로 비용을 두 번 낸다.

```bash
# 테스트 실행 전 항상 이 가드를 먼저 돌린다 (로컬 macOS 전용)
docker info > /dev/null 2>&1 || (open -a Docker && until docker info > /dev/null 2>&1; do sleep 2; done)
./gradlew test
```

- 이 가드는 **로컬 macOS 전용**이다 (`open -a Docker`). Linux / CI 는 자체 Docker 설정을 쓰므로 이 가드를 돌리지 않는다.
- 위 `until` 루프엔 타임아웃이 없다. Docker 가 한참 안 뜨면 (라이선스·리소스 문제 등) 무한 대기하므로, 사람이 직접 중단(Ctrl+C)하고 환경을 확인한다.
- "Docker 가 안 떠 있어서 통합 테스트를 생략한다" 로 귀결시키지 않는다.

## 모킹 / Stub 정책

**내부 컴포넌트는 모킹·stub 모두 금지하고 실제 빈으로 통합 테스트한다.** 외부 호출 경계(외부 HTTP API · 메시징 · 결제 등 우리 애플리케이션 바깥의 의존성)는 격리해야 하며, 격리 도구로 **프로그래머블 stub 을 기본**으로 한다.

### 규칙
- 외부 호출 경계는 **프로그래머블 stub**(`@TestConfiguration` + 빈 교체) 으로 격리한다. 인터페이스(`ProductExtractor`)에 stub 구현(`StubProductExtractor`)을 만들어 빈으로 등록하고, stub 의 응답을 람다로 받아 매 테스트가 시나리오별로 교체한다.
- **stub 빈에는 `@Primary` 를 붙인다.** 운영 `@Component` 빈과 타입이 같아 주입 후보가 2개가 되는데, `@Primary` 로 stub 우선을 명시한다. 빈 이름과 주입 지점 파라미터명이 우연히 일치하는 데 기대면 파라미터명 리팩터링 시 격리가 조용히 깨진다 — `@Primary` 는 그 의존을 끊는다.
- **stub 의 default 람다는 throw 로 둔다.** 명시 세팅을 빠뜨리면 호출 시점에 즉시 `IllegalStateException` 으로 깨져 "이전 테스트의 build 가 살아남는" 함정을 차단한다. stub 을 호출하지 않는 테스트는 영향 없다.
  ```kotlin
  class StubProductExtractor : ProductExtractor {
      // default 가 의도된 시나리오인 양 동작 가능한 값을 두면 명시 세팅을 빠뜨려도 통과해버린다.
      // 명시 세팅을 강제하기 위해 default 자체를 throw 로 둔다.
      var build: (ProductLink) -> Product = {
          error("stub.build 를 테스트 본문에서 명시 세팅해야 한다.")
      }
      override fun extract(link: ProductLink): Product = build(link)
  }

  // 테스트 본문
  stubExtractor.build = { link -> Product(link, name = "나이키", price = 99_000) }
  ```
- `@Mock` / `@MockBean` / `@SpyBean` 사용은 다음 두 조건을 **모두** 만족할 때만 허용한다.
  1. 인터페이스 메서드 수가 많아 stub 구현 부담이 크거나, 호출 횟수·순서 검증이 시나리오의 본질인 경우
  2. `IntegrationStubs` 에 한 번 등록해 모든 통합 테스트가 같은 mock 인스턴스를 공유함으로써 컨텍스트 캐싱이 깨지지 않을 때
- 클래스별 `@MockBean` 으로 컨텍스트 캐시를 깨는 패턴은 금지한다.
- 컨트롤러 테스트도 서비스를 모킹·stub 하지 않는다. `IntegrationTestSupport` + MockMvc 로 실제 흐름을 검증한다.

### Stub 이 mock 보다 우월한 이유
- **응답 고정의 함정 회피** — 람다 교체로 시나리오별 동적 응답이 가능해 `when().thenReturn()` 과 표현력이 사실상 동등하다.
- **시그니처 변경 안전** — stub 은 인터페이스 구현이라 컴파일 타임에 깨짐을 잡는다. mock 은 reflection 기반이라 메서드 이름 오타·시그니처 변경이 런타임 실패로만 드러난다.
- **컨텍스트 캐시 보존** — stub 은 `IntegrationStubs` 한 곳에 등록되어 모든 통합 테스트가 같은 인스턴스를 공유한다. `@MockBean` 은 빈 그래프를 변경시켜 캐시를 깬다.
- **디버깅 용이** — stub 은 실제 인스턴스라 디버거에서 내부가 보이고 IDE 의 reference 탐색이 동작한다.
- 호출 검증이 필요하면 stub 에 카운터 필드를 추가한다 (`val invocations = mutableListOf<...>()`). `verify` 와 동등.

### 이유 (왜 내부는 금지인가)
- 모킹·stub 은 본질적으로 "이 호출이 이렇게 동작한다"는 가정 코드다. 가정과 실제의 어긋남이 통합 시점에 드러나면 가장 비싸다.
- 내부 컴포넌트를 모킹·stub 하면 리팩터링이 깨져도 테스트는 초록불을 유지하는 사고가 잦다.
- 외부 호출만 격리하고 그 외는 실제로 돌리는 것이 회귀 검출력의 균형점이다.

## 스프링 컨텍스트 캐싱

**모든 통합 테스트는 `IntegrationTestSupport` 단일 컨텍스트를 공유한다.** 컨텍스트가 한 번만 부팅되고 캐시 재사용되어 빌드 시간이 크게 줄어든다.

### 규칙
- 클래스별 `@Import(...)` / `@TestConfiguration` / `@MockBean` 으로 컨텍스트 변형 금지. Spring 은 빈 그래프가 다르면 새 컨텍스트를 부팅한다.
- 외부 호출 stub 빈은 `support/IntegrationStubs` 한 곳에 등록한다. `IntegrationTestSupport` 가 이를 import 하므로 모든 통합 테스트가 같은 인스턴스를 공유한다.
- `@DirtiesContext` 금지. 캐시를 폭파해 다음 테스트가 컨텍스트 재부팅 비용을 부담한다.
- `@ActiveProfiles` 분기 금지. 다른 프로파일은 다른 컨텍스트로 캐싱된다.
- `@SpringBootTest(webEnvironment = ...)` 옵션 분기 금지. 옵션이 다르면 별개 컨텍스트.
- `@TestPropertySource` 로 클래스별 프로퍼티 변형 금지. 같은 이유.

### Stub 빈의 mutable 상태 다루기
같은 컨텍스트를 공유하면 `var` 필드를 가진 stub 빈이 클래스 간에 누수될 수 있다.
- 매 테스트가 stub 의 mutable state(`build` 람다 등)를 본문에서 명시적으로 세팅한다.
- 클래스 간 격리는 "다음 테스트가 자기 시나리오를 명시적으로 다시 세팅"으로 자연스럽게 보장된다.
- 다른 테스트의 stub state 에 의존하는 코드는 작성 금지.

## 테스트 셋업 원칙

**`@BeforeEach` / `@BeforeAll` / `@Sql` 등 셋업 hook 으로 fixture 데이터·mock 상태·인프라 객체를 미리 채우지 않는다.** 각 테스트 메서드가 자기 시나리오에 필요한 데이터·stub·MockMvc 등을 본문에서 직접 만든다.

### 규칙
- DB 격리는 클래스 레벨 `@Transactional` 의 자동 롤백으로 해결한다. `@BeforeEach` 에서 `deleteAll()` 류로 정리하지 않는다.
- 공유 mutable 상태(Spring bean 으로 주입된 stub 의 람다 등)는 매 테스트에서 명시적으로 세팅한다. 셋업 hook 에서 default 로 되돌리는 구조 금지.
- MockMvc / RestAssured 포트 / 테스트용 client 같은 인프라 객체도 셋업 hook 대신 각 테스트 본문에서 만든다.

### 이유
- 테스트 메서드 하나만 봐도 시나리오를 완전히 이해할 수 있어야 한다.
- 셋업 hook 은 한 테스트의 변경이 다른 테스트로 누수되는 함정을 만든다.
- "암묵적 사전 상태"를 없애 회귀 추적·리뷰 비용을 낮춘다.

## 테스트 메서드 작성 규약

### 네이밍

**클래스명 — 단위만 "대상 + Test", 나머지는 분류를 드러내는 접미사를 붙인다.** 파일명이 분류의 single source 이고(메타 테스트가 파일명으로 분류별 규칙을 강제한다), 통합 테스트는 단일 클래스가 아니라 시나리오를 검증하므로 대상 클래스명 하나로 못 짓는다.

| 분류 | 규칙 | 예 |
|---|---|---|
| 단위 | `{대상클래스}Test` | `ProductLinkTest` |
| 통합 | `{기능·시나리오}IntegrationTest` | `WishRegisterIntegrationTest` |
| E2E | `{대상}E2ETest` | `ProductLinkExtractE2ETest` |
| 동시성 | `{대상}ConcurrencyIntegrationTest` | `TournamentStartConcurrencyIntegrationTest` |
| 타이밍 | `{대상}TimingIntegrationTest` | — |
| 직렬화(조건부) | `{대상}SerializationCompatibilityTest` | — |

**메서드명 — 한국어 backtick 으로 시나리오를 한 문장으로 표현한다.**
```kotlin
@Test
fun `같은 guest 가 같은 URL 을 두 번 등록하면 409 CONFLICT 가 반환된다`() { ... }
```

### 단언
- **`kotlin.test` 를 기본으로 한다.** 단순 단언(`assertEquals`, `assertNotNull`, `assertFailsWith`)은 `kotlin.test` 가 짧고, 코드베이스 실태도 사실상 `kotlin.test` 단독이다.
- AssertJ(`assertThat(...)`)는 **컬렉션 비교 · 객체 그래프 깊은 비교 · soft assertions 처럼 표현력 차이가 큰 경우에 한해** 쓴다. 단순 동등 비교를 AssertJ 로 풀지 않는다. 둘 다 `spring-boot-starter-test` 에 포함되어 추가 의존성은 없다.
- 한 테스트 메서드 안에서는 두 스타일을 섞지 않는다 (가독성 일관성).
- Kotest · Strikt 같은 Kotlin 전용 테스트 라이브러리는 별도 의존성이라 이후 도입 예정. 현재는 사용 금지.

### 환경 의존 테스트
- 실제 외부 API 호출이 들어가는 테스트(예: Gemini)는 `@EnabledIfEnvironmentVariable(named = "...", matches = ".+")` 로 환경 의존을 격리한다.
- `@Disabled` 는 환경과 무관하게 영구 비활성화 의미. 사용 시 이유를 어노테이션 인자에 명시한다.

### 동시성 · 시간 의존 통합 테스트

race · 동시성 · timeout 처럼 일반 통합 테스트 패턴(`@Transactional` 자동 롤백 + 단일 스레드)으로 표현이 어려운 시나리오는 **별도 분류**로 명시적으로 작성한다. "검증이 어려우니 생략한다" 로 귀결시키지 않는다.

#### 규약
- 클래스명·패키지로 명확히 구분: `*ConcurrencyIntegrationTest` / `*TimingIntegrationTest`.
- `IntegrationTestSupport` 를 그대로 상속해 컨텍스트 캐시를 보존한다. 단 `@Transactional` 은 사용하지 않는다 — 별도 트랜잭션 동시 진행이 시뮬레이션의 본질이기 때문.
- 데이터 격리는 자기가 만든 행을 메서드 끝에서 명시적으로 정리하거나, 매 테스트가 격리된 식별자(예: 새 `UUID guestId`)를 사용한다.
- 동시 호출 시뮬레이션은 `ExecutorService` + `CountDownLatch` 로 동시 출발을 강제한다. `Thread.sleep()` 으로 타이밍을 맞추지 않는다.
- timeout 검증은 JUnit `@Timeout` 또는 Awaitility 를 사용한다. wall clock 비교(`System.currentTimeMillis()` 차이) 금지.

## 테스트 컨벤션 기계 강제 — `TestConventionTest`

위 테스트 규칙 중 **오탐 없이 기계로 PASS/FAIL 을 가를 수 있는 불변식**은 `support/TestConventionTest` 가 src/test 소스를 스캔해 강제한다 (모킹·셋업 hook 등 금지 규칙은 import 라인, E2E 격리는 어노테이션이 실제로 붙었는지). 이 메타 테스트는 `./gradlew test` 에 포함되어 PR CI 에서 돌므로, 위반은 머지 전에 빨간불로 드러난다. Spring 컨텍스트를 띄우지 않아 Docker 없이 단독 실행도 가능하다 — `./gradlew test --tests "com.depromeet.piki.support.TestConventionTest"`.

**정본은 `TestConventionTest.kt` 코드다 — 아래 목록은 그 정본을 옮겨 적은 요약일 뿐이다.** 목록과 코드가 어긋나면 코드가 이긴다. 아래는 편의상 훑어보는 용도로만 쓰고, 실제 판정 로직은 항상 `TestConventionTest.kt` 를 직접 확인한다.

현재 강제하는 불변식 (요약):
- 모킹 라이브러리(mockk · Mockito · springmockk) import 금지
- `@MockBean` / `@SpyBean` / `@MockitoBean` / `@TestBean` · `@DirtiesContext` · `@ActiveProfiles` · `@TestPropertySource` import 금지 (컨텍스트 캐시 보존)
- `@SpringBootTest` 는 `IntegrationTestSupport` 한 곳에만 선언
- `*IntegrationTest` 는 `IntegrationTestSupport` 상속
- `@BeforeEach` / `@BeforeAll` 셋업 hook 금지
- `*E2ETest` 는 `@Disabled` 또는 `@EnabledIfEnvironmentVariable` 로 격리 (무방비 E2E 가 CI 에서 실제 외부 호출을 돌리는 것을 차단)

**규칙을 더하거나 바꿀 때**: 그 규칙이 기계로 오탐 없이 판정 가능하면 산문에만 적지 말고 `TestConventionTest` 에 케이스를 더한다(결정론 층으로 내린다 — `CLAUDE.local.md` "층" 원칙). 사람·모델 판단이 끼는 규칙(서비스 단독 테스트 여부, 한국어 네이밍 적정성 등)만 산문에 남긴다.

### 강제력의 범위와 한계

이 게이트가 닫는 것과 못 닫는 것을 분명히 둔다.

- **강제됨**: 메타 테스트는 `./gradlew test` 에 포함되고 `dev` 는 `build`·`test` 를 required status check(strict)로 두므로 **PR 머지 경로에서 위반이 막힌다.** 통합 테스트가 되려면 `@SpringBootTest` 가 필요한데 그건 `IntegrationTestSupport` 한 곳에만 허용되므로, 파일명을 어떻게 짓든 통합 테스트는 그 베이스를 거친다 (네이밍 회피로 빠져나가지 못한다).
- **왜 import 텍스트 스캔인가**: Docker·Spring 부팅 없는 가벼움을 우선한 선택이다. 대가로 (a) `@org.mockito.Mock` 같은 풀패키지 인라인 참조, (b) denylist 에 없는 새 모킹 라이브러리는 못 잡는다. 상속·패키지 의존 같은 타입 그래프 수준을 근본 강제하려면 ArchUnit 으로 승격하는 게 정석이며, 의존성 추가가 필요한 별도 작업이다.
- **안 닫히는 인프라 구멍**: `enforce_admins:false` 라 관리자 직접 push 는 게이트를 우회한다. PR 승인 요구가 0 이라 에이전트가 자기 PR 을 머지할 수도 있다. 둘 다 branch protection 설정 변경(팀 결정)이 필요하다.
- **자기 참조의 한계**: 메타 테스트 자체를 약화·삭제하는 PR 은 약해진 채로 CI 가 초록불이다. 코드로 자기무결성을 완전히 보증할 수 없다 — **이 파일(`TestConventionTest`)의 변경은 사람 리뷰가 책임진다.** 약화가 필요하면 먼저 규칙을 산문에서 바꾼 뒤 메타 테스트를 따라 고친다.

