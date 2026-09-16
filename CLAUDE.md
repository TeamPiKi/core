<!--
본 문서는 프로젝트 전반의 코딩·테스트 컨벤션을 담는다. 세션마다 항상 로드되므로 상주할 값어치가 있는 것만 둔다.
특정 파일을 다룰 때만 필요한 상세 규약은 `.claude/rules/*.md` 로 분리하고(`paths:` frontmatter 로 그 파일을
열 때 자동 로드), 본 파일에는 핵심 불변식과 정본 위치를 가리키는 스텁만 남긴다.
-->

# 프로젝트 컨벤션

## Null 처리 원칙

**`== null` / `!= null` 분기를 제거한다.** 모든 nullable 처리는 Elvis(`?:`) + early return / throw / default 로 표현한다.

### 규칙
- **`== null` / `!= null` 사용 금지** — Elvis(`?:`)로 대체한다.
- **`requireNotNull` / `checkNotNull` 은 허용한다.** "non-null이어야 한다"는 의도가 시그니처에 명확히 드러나는 Kotlin 표준 idiom이며, 금지 대상은 어디까지나 `== null` / `!= null` 분기 패턴 한정이다.
- **Elvis + early return 패턴을 기본으로 한다.**
  ```kotlin
  // 금지
  if (value == null) return Default
  val x = if (value == null) throw E() else value

  // 권장
  value ?: return Default
  val x = value ?: throw E()
  ```
- 복합 조건이 필요해 보이면 함수를 분해해 **guard clause 여러 줄**로 푼다.
  ```kotlin
  fun toField(value: T?, box: Box?): Field<T> {
      value ?: return Field.NotFound
      box ?: return Field.Inferred(value)
      return Field.Extracted(value, box.toBoundingBox())
  }
  ```
- `sealed class` / `sealed interface` 분기는 `when` + `is` 를 사용한다. (null 체크와는 무관)

### 예외
- 외부 라이브러리 시그니처가 강제하는 경우 (예: `Optional.isPresent()` 같은 Java interop)
- 이 경우에도 **주석으로 이유를 명시**한다.

## 도메인 예외 정책 — `require` / `check` / `error` vs 커스텀 예외

**판단 기준 한 줄: "멀쩡한 클라이언트가 정상 요청으로 여기 닿을 수 있나?"**

- 닿는다 → **계약** → 커스텀 예외 (`*Exception.factoryMethod()`, 400 / 409 / 403 등)
- 못 닿는다 → **불변식** → `require` / `check` / `error` (500, 의도된 코드 버그 신호)

| 상황 | 누가 터뜨리나 | 범주 | 도구 | 결과 |
|---|---|---|---|---|
| `error(MISSING_ID)` — 영속화 전 `getId()` | 개발자(버그) | 불변식 | `error` | 500 |
| 닉네임 17자 | 클라이언트 | 계약 | 커스텀 | 400 |
| 이미 완료된 토너먼트에 재요청 | 클라이언트 | 계약 | 커스텀 | 409 |
| `winnerId` 가 참가 목록에 없음 (서비스가 보장한 값) | 개발자(버그) | 불변식 | `require` | 500 |

### 규칙
- `require` 로 우연히 400 이 나오는 건 캐치올 핸들러 덕분. throw 지점에 "이건 400이다"가 박혀 있지 않다. 커스텀 예외는 `status` · `category` 가 코드에 박힌다.
- **도메인이 자기방어** 한다. 도메인 메서드가 직접 커스텀 예외를 던지면 호출 위치(서비스 / 다른 도메인 / 테스트)에 무관하게 같은 결과가 나온다. 서비스에서 `check` 와 같은 조건을 사전 `if` 로 막는 패턴은 도메인에 동일 검증을 옮긴 뒤 제거 가능.
- **한 메서드 안에 `require` 와 커스텀 예외가 공존하는 게 정상.** 각 줄이 다른 질문("누가 터뜨리나")에 답하고 있을 뿐.
  ```kotlin
  fun complete(winnerWishItemId: Long) {
      if (isCompleted()) throw TournamentException.alreadyCompleted()      // 계약: 클라이언트 도달 가능 → 409
      require(winnerWishItemId in wishItemIds) { "우승자가 참가 목록에 없음" }  // 불변식: 서비스가 보장 → 500
  }
  ```

### 검증은 입력 경계와 엔티티 양쪽에 둔다

같은 조건을 두 번 검증해도 된다 — 각 층이 다른 질문에 답하면 중복이 아니라 다층 방어다.

- **입력 경계** (컨트롤러 요청 DTO, 외부 추출 파이프라인 등) — *계약* 검증. 각 입력 경로가 자기 경계에서 책임진다. 생성 경로가 새로 늘면 그 경로가 자기 계약 검증을 더한다.
- **엔티티 생성자** — *불변식* 검증(`require`). 엔티티는 누가 어떤 경로로 만들든 스스로 유효함을 보장하는 최후의 보루다. 정상 흐름에선 경계가 다 걸러 여기 닿지 않는다. 닿았다면 어떤 경계가 검증을 빠뜨린 것이므로 `500`.
- 엔티티 생성자에 HTTP status 같은 전송 계층 계약을 박지 않는다. status 는 각 입력 경계가 정한다.

### 한 줄 외울 것
코드 모양 보지 말고 **"멀쩡한 클라이언트가 정상 요청으로 여기 닿을 수 있나?"** 만 물을 것. 닿으면 커스텀, 못 닿으면 `require` / `check` / `error`.

### 예외 클래스·message·code 를 만질 때

`private` 생성자 + `companion object` 정적 팩토리로만 만들고, 팩토리는 도메인 `*ErrorCode` enum 의 코드 하나만 참조한다(번호는 append-only). message 는 고정된 사용자 대면 문구로 두고 내부 정보를 담지 않는다. 구분은 로그·메트릭으로 한다. 상세(예외 이름·생성 패턴·메시지 톤·에러 코드 규칙)는 `.claude/rules/domain-exception.md` 가 `*Exception.kt`·`*ErrorCode.kt`·`GlobalExceptionHandler.kt` 등을 다룰 때 자동 로드된다.

## 가까운 미래는 고려한다

YAGNI 는 **가설적·먼 미래**(올지 안 올지 모르는 요구)를 위한 추상화·일반화를 만들지 말라는 것이지, 모든 미래를 무시하라는 게 아니다.

- 이미 예정됐거나 진행 중인 **가까운 미래**(보류 이슈, 합의된 후속 작업 등)는 설계에서 고려한다 — 미리 구현하지는 않더라도, 그 미래가 와도 깨지지 않는 구조로 둔다.
- 구분: "정말 올지 모르는 것"은 무시, "올 게 거의 확실한 것"은 충돌하지 않게 설계한다.

## 기본 브랜치

**이 프로젝트의 기본 브랜치는 `dev`.** `main` 은 옛 상태에 머물러 PR / worktree 분기 base 로 사용하지 않는다. PR 은 항상 `dev` 를 향하고, 새 worktree·branch 도 `origin/dev` 기준으로 분기한다.

## 별도 작업은 worktree 로 분리

현재 브랜치의 목적과 다른 작업(다른 이슈·기능)을 요청받으면 곧장 현재 브랜치에 얹지 않고, `AskUserQuestion` 으로 worktree 분리를 첫 번째(recommend) 옵션으로 묻는다. 같은 이슈의 후속 단계면 묻지 않고 이어간다. 생성·진입·정리 절차는 `/issue`·`/session-close` 스킬과 루트의 로비 규칙(`.claude/rules/piki-workspace.md`)이 담당하고, 여기에는 이 repo 의 정책만 둔다.

- **분기 base 는 항상 `origin/dev`.** `git worktree add ... origin/dev` 로 만든 뒤 `EnterWorktree path=` 로 진입한다. 진입 여부는 분리를 묻는 그 질문에서 함께 확인하고, 묻지 않은 자동 진입은 하지 않는다. 진입하지 않고 메인 cwd 에 남으면 statusline·하단 경로·PR 표시가 전부 메인 브랜치 기준이 돼 작업 위치가 보이지 않으므로, 그 사실을 알리고 `git -C` 로 격리한다. `EnterWorktree name=` 단독 생성은 base 가 `worktree.baseRef` 설정에 의존해 `dev` 가 아닐 수 있다.
- **스택 브랜치는 쓰지 않는다.** 다른 feature 브랜치 위에 쌓지 않고, 의존하는 작업이 `dev` 에 머지될 때까지 기다린 뒤 분기한다(시퀀싱). 여러 사람이 squash/rebase 로 머지하는 환경에서 스택은 base 가 바뀔 때마다 하위 브랜치가 꼬인다. 기다릴 수 없으면 사용자에게 먼저 알린다.
- **정리는 이벤트에 얹는다.** 작업 종료·PR 머지 직후와 새 worktree 생성 직전에, clean 이고 머지·삭제된 브랜치인 worktree 만 제거한다. `--force` 는 쓰지 않고, dirty 면 작업 중일 수 있어 그대로 둔다. 주기 검사(타이머)는 두지 않는다.

## 의존성 관리

**버전 정보의 단일 진실 원천은 `build.gradle.kts`.** CLAUDE.md / README / 기타 문서에 버전 숫자를 박지 않는다. 버전이 궁금하면 `build.gradle.kts` 를 읽는다.

### 새 의존성 추가 시
- **Maven Central 에서 최신 안정 버전을 조회한 뒤 박는다.** LLM 학습 시점의 옛 버전을 그대로 쓰지 않는다. RC / Beta / Milestone / Alpha 등 pre-release 는 제외. 조회는 https://central.sonatype.com 과 https://search.maven.org 양쪽을 확인한다.
- Spring Boot 의 `dependencyManagement` BOM 이 이미 관리하는 의존성은 **버전을 직접 명시하지 않고 BOM 에 따른다.** BOM 이 안 잡아주는 의존성만 직접 라인 명시.
- 라인은 현재 프로젝트의 다른 의존성과 호환되는 것으로 고른다.

### 기존 의존성 버전 변경 시
- **버전 옆에 주석으로 고정 이유가 적혀 있으면 함부로 만지지 않는다.** 의도된 down-pin 일 가능성이 높다. 사용자에게 변경 이유와 호환성 확인 후 진행.
- 예: Testcontainers BOM 라인에 달린 고정 이유 주석(`build.gradle.kts`). 숫자는 그쪽이 정본이라 여기 옮기지 않는다.

## DB 스키마

FK 제약과 JPA 연관관계 어노테이션(`@ManyToOne` 등)을 쓰지 않는다. 테이블 관계는 raw ID 필드로만 잇고 참조 무결성은 서비스 계층이 책임진다. 마이그레이션은 Flyway(`src/main/resources/db/migration/`, 그 디렉터리의 `CLAUDE.md` 가 네이밍·배포 규약). 상세는 `.claude/rules/db-schema.md` 가 엔티티·마이그레이션 파일을 다룰 때 자동 로드된다.

## 트랜잭션 경계

**`@Transactional` 은 서비스 메서드 레벨에 둔다.** 조회 전용 메서드는 `@Transactional(readOnly = true)`. (메서드마다 readOnly 분기가 다르므로 클래스 레벨보다 메서드 레벨이 자연스럽다.)

### 외부 호출은 트랜잭션 밖에서
외부 호출 (LLM · HTTP fetch · 결제 등 우리 바깥 의존성) 을 트랜잭션 안에 넣지 않는다. read-timeout 이 길어 (예: extractor 원격 추출 호출) 그 동안 DB 커넥션을 잡으면 커넥션 풀이 고갈되어 다른 API 까지 latency 가 번진다.

- 외부 호출은 트랜잭션 바깥에서 끝내고, **영속화만 별도 빈에 위임**해 짧은 트랜잭션으로 묶는다.
- 예: `WishlistService.registerFromUrl` 은 트랜잭션 없이 추출을 끝낸 뒤 `WishPersistenceService.persist`(`@Transactional`) 로 영속화만 위임.

### self-invocation 주의
같은 빈 안에서 `@Transactional` 메서드를 직접 호출하면 Spring AOP proxy 를 거치지 않아 트랜잭션이 무력화된다. 경계를 분리하려면 **별도 빈으로 추출**해 proxy 를 거치게 한다.

## 로깅

### Logger 선언
`private val log = LoggerFactory.getLogger(javaClass)` 로 통일한다.

### 민감 정보는 마스킹해서 찍는다
URL · 토큰 · 사용자 입력 원본 등 민감 정보를 로그에 그대로 남기지 않는다. URL 은 `ProductLink.safeLogString()` (host + path 만, 쿼리스트링 제외) 처럼 마스킹 헬퍼를 거친다.

- 이유: URL 쿼리스트링에 인증 토큰이 실릴 수 있어 raw 로깅 시 누출된다. (`.claude/rules/domain-exception.md` 의 "메시지 톤: 응답 detail 은 전부 사용자 대면, 개발자 구분은 로그로" 와 같은 결)

### 레벨 기준
- **info** — 정상 흐름·지표 (latency 등), 클라이언트 계약 위반 (검증 실패·도메인 예외). 클라이언트 잘못은 서버 입장에선 정상 동작이라 info.
- **warn** — 외부 호출 실패·재시도, 방어적으로 차단한 비정상 요청 (SSRF 등).
- **error** — 예상 못한 서버 버그. 스택 트레이스를 함께 남긴다 (`log.error(msg, e)`).

### SLF4J placeholder
문자열 연결 대신 `{}` placeholder + 파라미터 바인딩을 쓴다 (`log.info("latency={}ms", ms)`).

## 도메인 용어

- **product** — 외부 상품(쇼핑몰 페이지)과 그 추출 파이프라인. `ProductLink`(외부 URL) · `ProductLinkExtractor` · `ProductSnapshot`(추출 시점 결과).
- **item** — 상품의 정체성(`link`). 추출값·상태·이력은 버전(`ItemSnapshot`)이 들고, item 은 wish · tournament 가 참조하는 안정적 식별 단위다.
- **item_snapshot** (`ItemSnapshot`) — item 의 한 추출 버전(name · price · image · currency · status · extracted_at · created_by). item 갱신 때마다 새 행이 쌓여 가격·이름 이력을 보존한다. 화면값은 버전들에서 계산한다(`ItemVersions`, 내 맥락의 값 vs 공유 READY). wish 는 item 을 참조하고 "기다리는 행"(`waitingSnapshotId`)만 따로 들며, tournament_item 은 출전 시점 고정 버전(pin)을 가리킨다.
- **wish** — user 가 item 을 위시리스트에 담은 기록 (`user_id` + `item_id`).
- **tournament** — item 들로 겨루는 토너먼트. `tournament_item`(출전 아이템) · `tournament_user`(참여자).

추출 결과(`ProductSnapshot`)를 영속화하면 그 상품의 `item`(정체성)과 `ItemSnapshot`(버전)이 된다. 외부 경계를 가리키는 이름에 `item` 을, 우리 엔티티에 `product` 를 쓰지 않는다.

## 테스트

테스트 규약은 두 파일로 나뉜다. **원칙은 infra 정본**(분류·가치 판단·결정 트리·모킹 금지·셋업·네이밍·기계 강제 + JVM/Spring 공통)이고 `install.sh` 가 설치하며 아래로 항상 로드된다. **이 repo 의 Kotlin·MySQL 바인딩**(좌표·단언 라이브러리·stub 형태·통합 테스트 세부)은 `.claude/rules/testing-convention.md` 가 갖는다.

**테스트를 작성·수정하기 전에 `.claude/rules/testing-convention.md` 를 읽는다.** 이 파일은 `src/test/**` 의 기존 파일을 열면 자동으로 붙지만, 새 테스트 파일을 곧장 생성하는 경로에서는 안 붙는다(실측). 그 경우 직접 읽어야 규약이 적용된다.

테스트는 항상 단위 + 통합을 함께 돌리고, Testcontainers 가 Docker 를 요구하므로 데몬을 먼저 확인한다 (로컬 macOS 전용 가드):

```bash
docker info > /dev/null 2>&1 || (open -a Docker && until docker info > /dev/null 2>&1; do sleep 2; done)
./gradlew test
```

@.claude/rules/testing-principles.md

## DTO ↔ 도메인 매핑

**매핑 로직은 DTO 자신에 둔다. 별도 Mapper 클래스/빈을 만들지 않는다.** "받는 쪽이 매핑을 책임진다" 가 기준:

- **도메인 → 응답 DTO**: 응답 DTO 의 `companion object` 에 `from(도메인)` 정적 팩토리. 예: `UserResponse.from(user)`, `WishItemResponse.WishView.from(wish)`.
- **요청 DTO → 도메인/커맨드**: 요청 DTO 의 `toXxx()` 인스턴스 메서드. 예: `CreateTournamentRequest.toCreateTournament()`.
- **외부 응답 → 도메인**: 외부 결과 객체의 `toXxx()`. 예: 원격 추출 응답 DTO 의 `toProductSnapshot(link)` (`RemoteExtractionContract.kt`).
- **스냅샷·도메인 → 엔티티**: 받는 엔티티의 `companion object` 정적 팩토리. 예: `ItemSnapshot.pending(...)`, `ItemSnapshot.manual(...)`.

매핑 분기·정규화는 단위 테스트로 검증한다 (`.claude/rules/testing-principles.md` 의 "테스트 분류" 가 말하는 매퍼 함수 분기).

## 컨트롤러 / OpenAPI 문서

**컨트롤러는 `*Api` 인터페이스를 구현하고, 모든 응답은 `ApiResponseBody` 래퍼로 감싼다(204 금지). `*Api.kt` 는 도달 가능한 모든 응답(성공 + 실패)을 `@ApiResponse` + `*ApiExamples` 로 전수 문서화한다.** 상세 규약(인터페이스/구현체 어노테이션 분리 · example 객체화 · 응답 전수 문서화 조사 대상 · fail detail single source)은 `.claude/rules/openapi-controller.md` 에 있고, `*Api.kt` · `*Controller.kt` · `*ApiExamples.kt` · `SecurityConfig.kt` 를 다룰 때 자동 로드된다. 그 파일들을 직접 열지 않는 경로로 엔드포인트·응답·예외 계약을 바꿀 때는 직접 읽는다.

## 웹 요청 경계

경로 판정·CSRF·세션 고정·permitAll·SSR 예외 처리·필터 순서·브라우저 폴링·CDN SRI 에서 실제로 났던 결함과 그 교정은 `.claude/rules/web-request-boundary.md` 에 있다. `SecurityConfig`·필터·admin 패키지·템플릿을 다룰 때 자동 로드되고, 그 파일들을 열지 않는 경로로 보안 설정을 바꿀 때는 직접 읽는다. 기계가 판정할 수 있는 것(X-Forwarded-For 직접 읽기·`th:utext`·`param.x` 직접 비교·admin 빈의 조건 어노테이션 누락)은 `.claude/settings.json` 훅이 차단한다.

## PR 생성·갱신

**PR 생성·갱신은 항상 `/pr` 스킬로 한다.** 스킬을 쓸 수 없는 상황이면 수동 `gh` 로 우회하지 말고 사용자에게 먼저 묻는다.
