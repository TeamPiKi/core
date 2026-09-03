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

### 도메인 예외 이름

도메인 커스텀 예외는 `{도메인 명사}Exception` 으로 짓는다 — `ProductLinkException` · `WishException` · `ProductSnapshotException` · `TournamentException` · `UserException`. 행위명(`...ExtractionException` 등)이 아니라 도메인 용어(명사)를 쓴다.

### 도메인 예외 생성

커스텀 예외는 `*Exception : BaseException, HttpMappable` 패턴이다. 생성자를 `private` 으로 막고 `companion object` 의 **정적 팩토리 메서드**로만 만든다. 각 팩토리는 사유 하나를 나타내며 그 사유에 맞는 message·`ErrorCategory`·`HttpStatus` 를 한 자리에 박는다.

```kotlin
class WishException private constructor(
    message: String,
    override val category: ErrorCategory,
    override val httpStatus: HttpStatus,
) : BaseException(message), HttpMappable {
    companion object {
        fun alreadyExists(): WishException =
            WishException("이미 위시리스트에 등록된 상품입니다.", ErrorCategory.CONFLICT, HttpStatus.CONFLICT)
    }
}
```

호출부는 `throw WishException.alreadyExists()` 처럼 사유 이름만 읽으면 되고, status·메시지는 throw 지점에 흩어지지 않고 예외 클래스 한 곳에 모인다.

### 메시지 톤: 응답 detail 은 전부 사용자 대면, 개발자 구분은 로그로

도메인 예외의 message 는 `GlobalExceptionHandler` 를 거쳐 응답 `detail` 로 클라이언트에 그대로 나간다. **누가 어떤 이유로 닿든 사용자가 본다고 가정**하고, status·원인과 무관하게 **모든 detail 은 고정된 사용자 친화 문구**로 둔다. 사용자 입력 검증이든, 앱이 잘못 구성한 프로토콜 필드(OAuth 흐름·`all`/`ids`·provider 경로·상태 param)든, 외부 의존성 실패든 마찬가지다. 예외 message 에 LLM 원문·사용자 입력 원본·내부 식별자·구체적 검증 사유·내부 파라미터 이름·기술 용어 등 민감하거나 내부적인 정보를 담지 않는다. 디버깅에 필요한 구체 정보는 응답이 아니라 로그·cause 체인으로 남긴다.

- 나쁜 예: `untrustworthyValue(reason: String)` — 호출부가 임의 문자열을 message 에 실어 보낼 수 있어, 향후 LLM 원문·입력값이 응답으로 샐 수 있다.
- 좋은 예: 인자 없는 고정 message 팩토리. 사유 구분이 꼭 필요하면 노출돼도 안전한 enum/code 로 받는다.

개발자가 구분·디버깅해야 할 내부 정보(어느 흐름·필드·상태가 잘못됐나, 어느 단계 실패인가)는 **응답이 아니라 로그·cause·메트릭으로 분리**한다.

- **매 요청 단위 디버깅**이 필요하면 던지는 지점에서 로그(레벨은 `## 로깅` 기준: 클라 계약 위반은 info)나 cause 로 남긴다.
- **분류별 빈도·추세**가 필요하면(예: OAuth 실패가 어느 단계에 몰리나) 매 건 로그가 아니라 메트릭으로 집계한다.
- 앱 구현 버그(둘 다 보냄 같은)는 보통 앱 개발자가 자기 요청으로 알 수 있어, 서버가 응답·로그로 굳이 구분해 줄 필요가 없다. 정말 필요한 구분만 분리한다.

한 줄: **detail 은 사용자에게, 구분은 로그·메트릭에.** 디자이너·기획 문구 카탈로그를 적용할 때 "이건 앱 영역이니 기술 문구로" 같은 예외를 두지 않는다. 그런 구분은 detail 이 아니라 로그가 책임진다.

### 검증은 입력 경계와 엔티티 양쪽에 둔다

같은 조건을 두 번 검증해도 된다 — 각 층이 다른 질문에 답하면 중복이 아니라 다층 방어다.

- **입력 경계** (컨트롤러 요청 DTO, 외부 추출 파이프라인 등) — *계약* 검증. 각 입력 경로가 자기 경계에서 책임진다. 생성 경로가 새로 늘면 그 경로가 자기 계약 검증을 더한다.
- **엔티티 생성자** — *불변식* 검증(`require`). 엔티티는 누가 어떤 경로로 만들든 스스로 유효함을 보장하는 최후의 보루다. 정상 흐름에선 경계가 다 걸러 여기 닿지 않는다. 닿았다면 어떤 경계가 검증을 빠뜨린 것이므로 `500`.
- 엔티티 생성자에 HTTP status 같은 전송 계층 계약을 박지 않는다. status 는 각 입력 경계가 정한다.

### 한 줄 외울 것
코드 모양 보지 말고 **"멀쩡한 클라이언트가 정상 요청으로 여기 닿을 수 있나?"** 만 물을 것. 닿으면 커스텀, 못 닿으면 `require` / `check` / `error`.

### 에러 코드 (code 기반 에러 응답)

에러 응답은 사용자 문구가 아니라 **code**(예: `USER-001`)로 사유를 식별한다 — 문구는 클라가 code 로 매핑해 소유한다. 도메인 예외를 만질 때:

- **예외는 도메인 `*ErrorCode` enum 의 코드 하나를 참조**한다 — `code`·`category`·`message` 를 그 엔트리 한 곳에(single source), 팩토리는 코드만 넘긴다.
- **번호는 append-only** — 재사용·재배치 금지, 결번 유지(코드가 클라 계약).
- **status 는 `ErrorCategory` 가 소유**(category → HttpStatus 1:1) — 예외는 직접 안 들고 파생한다.
- **성공 응답은 code 없음**(`null`) — code 는 에러 전용, 성공은 HTTP status + `data`.
- **enum message 는 개발자·문서용 정본**(사용자 최종 문구는 클라 소유) — `detail` 로도 나갈 수 있어 사용자 톤 유지·내부 식별자 금지.
- **`ApiResponseBody.code` 는 `String`**, enum 은 `fail(errorCode)` 경계에서만 받는다(enum 필드는 Jackson 이 이름을 뱉어 어긋남).
- 새 code 는 `@ApiResponse` 설명·전역 카탈로그에 반영. 미배정 도메인 현황은 에픽 #728.

## 가까운 미래는 고려한다

YAGNI 는 **가설적·먼 미래**(올지 안 올지 모르는 요구)를 위한 추상화·일반화를 만들지 말라는 것이지, 모든 미래를 무시하라는 게 아니다.

- 이미 예정됐거나 진행 중인 **가까운 미래**(보류 이슈, 합의된 후속 작업 등)는 설계에서 고려한다 — 미리 구현하지는 않더라도, 그 미래가 와도 깨지지 않는 구조로 둔다.
- 구분: "정말 올지 모르는 것"은 무시, "올 게 거의 확실한 것"은 충돌하지 않게 설계한다.

## 기본 브랜치

**이 프로젝트의 기본 브랜치는 `dev`.** `main` 은 옛 상태에 머물러 PR / worktree 분기 base 로 사용하지 않는다. PR 은 항상 `dev` 를 향하고, 새 worktree·branch 도 `origin/dev` 기준으로 분기한다.

## 별도 작업은 worktree 로 분리

현재 브랜치의 작업과 **무관한 별도 작업**(다른 이슈·기능)을 요청받으면, 곧장 현재 브랜치에 얹지 말고 **worktree 를 만들지 물어본다**. `AskUserQuestion` 으로 선택지를 제시하되 **worktree 생성을 recommend(첫 번째 옵션)** 로 둔다.

- **트리거**: 새 요청이 현재 브랜치의 목적과 다른 작업일 때만 묻는다. 현재 작업의 연속(같은 이슈/기능의 후속 단계)이면 묻지 않고 그대로 진행한다.
- 새 worktree·branch 는 `origin/dev` 기준으로 분기한다 (위 `## 기본 브랜치`).

### worktree 진입은 EnterWorktree 로 — statusline·cwd 정렬

worktree 생성을 물을 때, **그 worktree 로 세션을 진입(`EnterWorktree`)할지도 함께 묻는다.** 자동 진입하지 않고 항상 확인한다.

- **이유**: 셸 cwd 가 메인 체크아웃에 남으면 statusline·하단 경로·표시 PR 이 전부 메인 브랜치 기준이라, 정작 작업 중인 worktree 브랜치가 안 보여 작업이 엉뚱한 곳에 가는지 혼란스럽다. `git -C`/`gradlew -p` 로 worktree 를 정확히 다뤄도 표시는 안 맞는다.
- `EnterWorktree` 로 진입하면 세션 cwd·statusline·git·gradle 이 다 worktree 로 정렬되고 `-C`/`-p` 도 불필요하다. `git worktree add` 로 base(`origin/dev`)를 명시해 만든 뒤 `EnterWorktree path=...` 로 진입하면 base 도 확실하다 (`EnterWorktree name=...` 단독 생성은 base 가 `worktree.baseRef` 설정에 의존해 `dev` 가 아닐 수 있다).
- 사용자가 진입을 원치 않아 메인 cwd 를 유지하면, statusline 이 worktree 브랜치를 안 보여준다는 점을 미리 알리고 `git -C` 로 격리한다.
- **워크스페이스 루트(`piki/`)에서 시작한 세션은 이 원칙이 기본값이다** - 로비에서 `EnterWorktree(path=...)` 로 이 repo 의 worktree 에 들어와 작업하고, 다른 repo 로 갈 땐 로비를 거친다. 로비 규칙은 루트의 `.claude/rules/piki-workspace.md`(infra 정본)가 담당한다.

### 스택 브랜치는 쓰지 않는다

모든 branch·worktree 는 `origin/dev` 에서 분기하고, **다른 feature 브랜치 위에 쌓지 않는다** (B 의 PR 이 A 를 base 로 향하게 하지 않는다).

- 작업이 아직 머지되지 않은 다른 작업에 의존하면, **스택 대신 시퀀싱**한다 — base 가 `dev` 에 머지될 때까지 기다렸다가 `dev` 에서 분기한다.
- 기다릴 수 없을 만큼 급한 의존이면 임의로 쌓지 말고 **사용자에게 먼저 알린다.**
- 이유: 여러 사람이 squash/rebase 로 머지하는 환경에서 스택은 base 가 머지·force-push 될 때마다 하위 브랜치가 꼬인다. auto-restack 툴·규율 없이는 유지 비용이 이득을 넘는다.

### worktree 정리는 주기 검사 대신 이벤트에 얹는다

worktree 누적을 막되 **주기적 검사(타이머·cron)는 두지 않는다.** 정리는 이미 일어나는 두 이벤트에 piggyback 한다.

- **작업 종료 / PR 머지 직후** — 그 worktree 의 목적이 끝났으므로 그 자리에서 제거한다.
- **새 worktree 생성 직전** — 머지·삭제된 브랜치의 stale worktree 를 함께 prune 한다 (`git worktree prune` + 머지·gone 브랜치 worktree 제거).
- **안전 가드**: clean(커밋 안 된 변경 없음) + 머지·삭제된 브랜치인 worktree 만 제거한다. **절대 `--force` 를 쓰지 않는다.** dirty 면 작업 중일 수 있으므로 그냥 두고 넘어간다.
- 한계 인지: 작업이 중단돼 PR 이 안 난 worktree 는 위 두 이벤트에 안 걸려 남을 수 있다. 이는 다음 worktree 생성 시점에 정리되거나, 사용자가 직접 정리한다.

## 의존성 관리

**버전 정보의 단일 진실 원천은 `build.gradle.kts`.** CLAUDE.md / README / 기타 문서에 버전 숫자를 박지 않는다. 버전이 궁금하면 `build.gradle.kts` 를 읽는다.

### 새 의존성 추가 시
- **Maven Central 에서 최신 안정 버전을 조회한 뒤 박는다.** LLM 학습 시점의 옛 버전을 그대로 쓰지 않는다. RC / Beta / Milestone / Alpha 등 pre-release 는 제외. 조회는 https://central.sonatype.com 과 https://search.maven.org 양쪽을 확인한다.
- Spring Boot 의 `dependencyManagement` BOM 이 이미 관리하는 의존성은 **버전을 직접 명시하지 않고 BOM 에 따른다.** BOM 이 안 잡아주는 의존성만 직접 라인 명시.
- 라인은 현재 프로젝트의 다른 의존성과 호환되는 것으로 고른다.

### 기존 의존성 버전 변경 시
- **버전 옆에 주석으로 고정 이유가 적혀 있으면 함부로 만지지 않는다.** 의도된 down-pin 일 가능성이 높다. 사용자에게 변경 이유와 호환성 확인 후 진행.
- 예: Testcontainers BOM 의 `// ... 모듈이 따라올 때까지 testcontainers BOM 을 1.21.4 로 명시 고정.` 주석.

## 테이블 간 외래 키

**DB `FOREIGN KEY` 제약을 두지 않는다.** 테이블 간 관계는 논리적으로만 연결한다.

### 규칙
- 마이그레이션에 `CONSTRAINT ... FOREIGN KEY` 를 추가하지 않는다. 엔티티는 raw ID 필드(`itemId: Long` 등)로 다른 테이블을 참조하며, JPA 연관관계 어노테이션(`@ManyToOne` 등)도 쓰지 않는다.
- 조회 성능을 위한 인덱스(`KEY idx_*`)는 FK 와 무관하므로 그대로 둔다.
- 참조 무결성은 애플리케이션 코드(서비스 계층의 존재 검증 등)가 책임진다.

## DB 마이그레이션

**도구**: Flyway. **위치**: `src/main/resources/db/migration/`. 상세 규약(네이밍·out-of-order·commutative·forward-only·destructive 단계 배포)은 그 디렉터리의 `CLAUDE.md` 에 있고, 마이그레이션 파일을 다룰 때 자동으로 로드된다. **FK 제약은 절대 추가하지 않는다** (`## 테이블 간 외래 키` 참조).

## 트랜잭션 경계

**`@Transactional` 은 서비스 메서드 레벨에 둔다.** 조회 전용 메서드는 `@Transactional(readOnly = true)`. (메서드마다 readOnly 분기가 다르므로 클래스 레벨보다 메서드 레벨이 자연스럽다.)

### 외부 호출은 트랜잭션 밖에서
외부 호출 (LLM · HTTP fetch · 결제 등 우리 바깥 의존성) 을 트랜잭션 안에 넣지 않는다. read-timeout 이 길어 (예: Gemini 60s) 그 동안 DB 커넥션을 잡으면 커넥션 풀이 고갈되어 다른 API 까지 latency 가 번진다.

- 외부 호출은 트랜잭션 바깥에서 끝내고, **영속화만 별도 빈에 위임**해 짧은 트랜잭션으로 묶는다.
- 예: `WishlistService.register` 는 트랜잭션 없이 추출을 끝낸 뒤 `WishPersistenceService.persist`(`@Transactional`) 로 영속화만 위임.

### self-invocation 주의
같은 빈 안에서 `@Transactional` 메서드를 직접 호출하면 Spring AOP proxy 를 거치지 않아 트랜잭션이 무력화된다. 경계를 분리하려면 **별도 빈으로 추출**해 proxy 를 거치게 한다.

## 로깅

### Logger 선언
`private val log = LoggerFactory.getLogger(javaClass)` 로 통일한다.

### 민감 정보는 마스킹해서 찍는다
URL · 토큰 · 사용자 입력 원본 등 민감 정보를 로그에 그대로 남기지 않는다. URL 은 `ProductLink.safeLogString()` (host + path 만, 쿼리스트링 제외) 처럼 마스킹 헬퍼를 거친다.

- 이유: URL 쿼리스트링에 인증 토큰이 실릴 수 있어 raw 로깅 시 누출된다. (`## 도메인 예외 정책` 의 "메시지 톤: 응답 detail 은 전부 사용자 대면, 개발자 구분은 로그로" 와 같은 결)

### 레벨 기준
- **info** — 정상 흐름·지표 (latency 등), 클라이언트 계약 위반 (검증 실패·도메인 예외). 클라이언트 잘못은 서버 입장에선 정상 동작이라 info.
- **warn** — 외부 호출 실패·재시도, 방어적으로 차단한 비정상 요청 (SSRF 등).
- **error** — 예상 못한 서버 버그. 스택 트레이스를 함께 남긴다 (`log.error(msg, e)`).

### SLF4J placeholder
문자열 연결 대신 `{}` placeholder + 파라미터 바인딩을 쓴다 (`log.info("latency={}ms", ms)`).

## 도메인 용어

- **product** — 외부 상품(쇼핑몰 페이지)과 그 추출 파이프라인. `ProductLink`(외부 URL) · `ProductExtractor` · `ProductSnapshot`(추출 시점 결과).
- **item** — 상품의 정체성(`link`). 추출값·상태·이력은 버전(`ItemSnapshot`)이 들고, item 은 wish · tournament 가 참조하는 안정적 식별 단위다.
- **item_snapshot** (`ItemSnapshot`) — item 의 한 추출 버전(name · price · image · currency · status · extracted_at). item 갱신 때마다 새 행이 쌓여 가격·이름 이력을 보존한다. wish 는 활성 버전, tournament_item 은 출전 시점 고정 버전을 가리킨다.
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

- **도메인 → 응답 DTO**: 응답 DTO 의 `companion object` 에 `from(도메인)` 정적 팩토리. 예: `UserResponse.from(user)`, `TournamentInfoResponse.from(info)`.
- **요청 DTO → 도메인/커맨드**: 요청 DTO 의 `toXxx()` 인스턴스 메서드. 예: `CreateTournamentRequest.toCreateTournament()`.
- **외부 응답 → 도메인**: 외부 결과 객체의 `toXxx()`. 예: `GeminiExtractionResult.toProductSnapshot(link)`.
- **스냅샷·도메인 → 엔티티**: 받는 엔티티의 `from()`. 예: `Item.from(snapshot)`.

매핑 분기·정규화는 단위 테스트로 검증한다 (`## 테스트 분류` 의 매퍼 함수 분기).

## 컨트롤러 / OpenAPI 문서

**컨트롤러는 `*Api` 인터페이스를 구현하고, 모든 응답은 `ApiResponseBody` 래퍼로 감싼다(204 금지). `*Api.kt` 는 도달 가능한 모든 응답(성공 + 실패)을 `@ApiResponse` + `*ApiExamples` 로 전수 문서화한다 — 절대 규칙.** 상세 규약(인터페이스/구현체 어노테이션 분리 · example 객체화 · 응답 전수 문서화 조사 대상 · fail detail single source)은 `.claude/rules/openapi-controller.md` 에 있고, `*Api.kt` · `*Controller.kt` · `*ApiExamples.kt` · `SecurityConfig.kt` 를 다룰 때 자동 로드된다. 그 파일들을 직접 열지 않는 경로로 엔드포인트·응답·예외 계약을 바꿀 때는 직접 읽는다.

## 웹 요청 경계에서 반복해 틀리는 것

전부 실제로 이 repo 에서 한 번씩 났던 결함이다(#986·#988). 문법이 멀쩡하고 테스트도 초록불이라 **코드만 봐서는 티가 안 나는 종류**라 여기 못박는다.

### 판단이 필요해 사람·모델이 지켜야 하는 것

- **경로로 접근을 판정할 땐 raw `request.requestURI` 가 아니라 `UrlPathHelper` 정규화 경로를 쓴다.** dispatcher 는 디코딩 경로로 라우팅하므로 `/%61dmin/...` 이 필터만 건너뛰고 컨트롤러엔 닿는다.
- **상태를 바꾸는 요청은 fetch 여도 CSRF 를 면제하지 않는다.** "JSON API 라 토큰을 못 싣는다" 는 틀렸다 — 헤더로 실으면 된다.
- **신원이 올라가는 시점(로그인·grant)에 기존 세션을 버리고 새로 발급한다.** `getSession(true)` 만 부르면 공격자가 미리 심어둔 세션 id 에 권한이 얹힌다.
- **`permitAll` 매처·필터 예외는 실제로 서빙하는 대상이 있을 때만 둔다.** 빈 채로 두면 나중에 그 경로에 놓이는 것이 무인증 공개가 되고, 보안 설정에 이미 있어 의도한 것처럼 보인다.
- **SSR 컨트롤러(Thymeleaf 반환)는 계약 예외를 잡아 리다이렉트한다.** 안 잡으면 `@RestControllerAdvice` 가 화면을 raw JSON 으로 갈아치워 운영자가 페이지를 잃는다.
- **서블릿 필터의 `@Order` 는 유일값으로 둔다.** 값이 겹치면 순서가 비명세 규칙으로 갈려, 차단 필터가 로깅 필터 바깥으로 밀리면 차단 기록 자체가 안 남는다.
- **브라우저가 스스로 반복하는 요청엔 종료 조건과 상한을 둔다.** 대상이 사라져도 멈추지 않으면 탭 하나가 시간당 수천 건을 보낸다.
- **외부 CDN 자원은 버전을 고정하고 SRI 를 건다. 단 SRI 를 걸었으면 실제 로드를 눈으로 확인한다.** CORS 헤더를 안 주는 CDN 은 `crossorigin` 이 붙는 순간 리소스를 통째로 차단해, 검사 없이 두는 것보다 나쁜 결과가 된다.

### 훅이 차단하는 것 (`.claude/settings.json`)

기계가 오탐 없이 판정하므로 산문으로 반복하지 않는다. 차단 메시지가 옳은 형태를 알려준다.

- 클라이언트 IP 를 `X-Forwarded-For` 에서 직접 읽기 → `ClientIp.of`
- `th:utext` → `th:text` (값 출처가 DB 로 바뀌면 저장형 XSS)
- `${param.x == 'v'}` → `${param.x != null and param.x[0] == 'v'}` (`param.x` 는 `String[]` 이라 직접 비교는 항상 false)
- admin 패키지 빈의 `@ConditionalOnAdminEnabled` 누락

## PR 생성·갱신

**PR 생성·갱신은 항상 `/pr` 스킬로 한다.** 스킬을 쓸 수 없는 상황이면 수동 `gh` 로 우회하지 말고 사용자에게 먼저 묻는다.
