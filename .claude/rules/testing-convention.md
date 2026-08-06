---
paths: ["src/test/**"]
---

# 테스트 컨벤션 (core 바인딩)

**원칙은 여기 없다.** 분류·가치 판단·분기 위치 결정 트리·모킹 금지·셋업 원칙·네이밍 접미사·기계 강제, 그리고 JVM/Spring 공통(컨텍스트 캐싱·E2E 격리·동시성)은 전 repo 공통이라 infra 가 정본을 갖고, `install.sh` 가 `.claude/rules/testing-principles.md` 로 설치한다 (CLAUDE.md 가 그 파일을 import 해 항상 로드한다). 이 파일은 `src/test/**` 를 다룰 때만 로드된다.

이 파일은 그 원칙을 **이 repo 의 언어·스택에 묶는 부분**만 담는다 — Kotlin · Spring · MySQL. 둘이 어긋나 보이면 원칙이 이긴다.

## 좌표

| 무엇 | 어디 |
|---|---|
| 통합 테스트 베이스 (`@SpringBootTest` 유일 선언) | `support/IntegrationTestSupport` |
| 외부 stub 빈 등록 지점 | `support/IntegrationStubs` |
| 메타 테스트 (기계 강제 구현체) | `support/TestConventionTest` |
| 단위 테스트 위치 | 대상 도메인 객체와 같은 패키지 (예: `product/domain/ProductLinkTest.kt`) |

## Kotlin 바인딩

### 메서드명

Kotlin 은 backtick 식별자를 허용하므로 별도 표시 어노테이션 없이 메서드명 자체로 쓴다.

```kotlin
@Test
fun `같은 guest 가 같은 URL 을 두 번 등록하면 409 CONFLICT 가 반환된다`() { ... }
```

### 단언

- **`kotlin.test` 를 기본으로 한다.** 단순 단언(`assertEquals` · `assertNotNull` · `assertFailsWith`)은 이쪽이 짧고, 코드베이스 실태도 사실상 `kotlin.test` 단독이다.
- **AssertJ(`assertThat`)는 표현력 차이가 큰 경우에 한해** 쓴다 — 컬렉션 비교 · 객체 그래프 깊은 비교 · soft assertions. 단순 동등 비교를 AssertJ 로 풀지 않는다. 둘 다 `spring-boot-starter-test` 에 포함되어 추가 의존성은 없다.
- 한 테스트 메서드 안에서 두 스타일을 섞지 않는다 (가독성 일관성).
- Kotest · Strikt 는 별도 의존성이라 현재 사용 금지 (이후 도입 검토).

### stub 구현 형태

"기본 동작을 throw 로 둔다" 는 원칙의 Kotlin 구현:

```kotlin
class StubProductExtractor : ProductExtractor {
    // 동작 가능한 기본값을 두면 명시 세팅을 빠뜨려도 통과해버린다. 그래서 기본을 throw 로 둔다.
    var build: (ProductLink) -> Product = {
        error("stub.build 를 테스트 본문에서 명시 세팅해야 한다.")
    }
    override fun extract(link: ProductLink): Product = build(link)
}

// 테스트 본문
stubExtractor.build = { link -> Product(link, name = "나이키", price = 99_000) }
```

## DB 바인딩 (이 repo 는 MySQL 을 쓴다)

- **Testcontainers MySQL 한정.** H2 등 다른 DB 로 대체 금지 — 운영과 다른 DB 로 검증하면 방언·제약 차이가 그대로 통과한다.
- **DB 격리는 클래스 레벨 `@Transactional` 의 자동 롤백**으로 해결한다. `deleteAll()` 류 정리 코드를 두지 않는다 (원칙: 셋업 hook 금지).
- 동시성 테스트는 예외 — `@Transactional` 을 쓰지 않고, 자기가 만든 행을 메서드 끝에서 명시 정리하거나 격리된 식별자(새 `UUID guestId`)를 쓴다.

### 실행 전 사전 검증 (로컬 macOS)

**테스트는 항상 단위 + 통합을 함께 돌린다.** 단위만 따로 분리해 돌리지 않는다 — 분기 망라는 단위로 작성하되, 실행 시점엔 통합까지 함께 돌려 컨트롤러·DB 회귀를 잡는다.

Testcontainers 가 Docker 를 요구하므로 **데몬을 먼저 확인**한다. 없이 돌리면 "Gradle 부팅 → 컴파일 → 컨테이너 시도 → 실패 → Docker 켬 → 재실행" 으로 비용을 두 번 낸다.

```bash
docker info > /dev/null 2>&1 || (open -a Docker && until docker info > /dev/null 2>&1; do sleep 2; done)
./gradlew test
```

- 이 가드는 **로컬 macOS 전용**이다 (`open -a Docker`). Linux / CI 는 자체 Docker 설정을 쓰므로 돌리지 않는다.
- `until` 루프에 타임아웃이 없다. Docker 가 한참 안 뜨면(라이선스·리소스 문제 등) 무한 대기하므로 사람이 직접 중단하고 환경을 확인한다.
- **"Docker 가 안 떠 있어서 통합 테스트를 생략한다" 로 귀결시키지 않는다.**

메타 테스트만 따로 돌릴 때는 Spring·Docker 가 필요 없다:

```bash
./gradlew test --tests "com.depromeet.piki.support.TestConventionTest"
```

## 통합 테스트 작성 세부

- 엔드포인트 당 시나리오·계약 검증을 **3~5건 수준**으로 유지한다. 분기 망라 목적의 추가는 도메인 단위로 내린다.
- 응답 계약(`status` · `code` · `detail` · `data` 필드 모양)을 단언에 포함한다. 도메인 객체 단언만으로는 직렬화·예외 매핑 회귀를 못 잡는다.
- 검증 실패(400) · 비즈니스 예외(409 등) 케이스도 계약 검증에 포함한다.

## 직렬화/호환성 테스트의 현재 적용 여부

이 repo 는 `StringRedisTemplate` 로 **문자열만** 저장하므로 이 시점 기준 대상이 없다. 다만 고정된 사실이 아니다 — Bucket4j 버킷 상태처럼 **객체 직렬화 저장을 도입하는 PR 이 그 테스트를 함께 추가**한다. 스냅샷 고정·하위호환 규칙 등 원칙은 principles 문서에 있다.

## 메타 테스트가 이 repo 에서 강제하는 것

**정본은 `TestConventionTest.kt` 코드다** — 아래는 훑어보기용 요약이고, 어긋나면 코드가 이긴다.

- 모킹 라이브러리(mockk · Mockito · springmockk) import 금지
- `@MockBean` / `@SpyBean` / `@MockitoBean` / `@TestBean` · `@DirtiesContext` · `@ActiveProfiles` · `@TestPropertySource` import 금지 (컨텍스트 캐시 보존)
- `@SpringBootTest` 는 `IntegrationTestSupport` 한 곳에만 선언
- `*IntegrationTest` 는 `IntegrationTestSupport` 상속
- `@BeforeEach` / `@BeforeAll` 셋업 hook 금지
- `*E2ETest` 는 `@Disabled` 또는 `@EnabledIfEnvironmentVariable` 로 격리

**강제력의 범위**: 이 메타 테스트는 `./gradlew test` 에 포함되고 `dev` 가 `build`·`test` 를 required status check(strict)로 두므로 **PR 머지 경로에서 위반이 막힌다.** 통합 테스트가 되려면 `@SpringBootTest` 가 필요한데 그건 `IntegrationTestSupport` 한 곳에만 허용되므로, 파일명을 어떻게 짓든 통합 테스트는 그 베이스를 거친다 (네이밍 회피로 빠져나가지 못한다).

**안 닫히는 구멍**: `enforce_admins:false` 라 관리자 직접 push 는 게이트를 우회하고, PR 승인 요구가 0 이라 에이전트가 자기 PR 을 머지할 수도 있다. 둘 다 branch protection 설정 변경(팀 결정)이 필요하다.
