<!--
본 문서는 프로젝트 전반의 코딩·테스트 컨벤션을 한 파일에 모아둔다.
파일이 길어지면 `conventions/testing.md` 등으로 분리될 수 있다. 분리 시 본 파일에서는
링크로 대체한다.
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
- 라인은 현재 프로젝트의 다른 의존성과 호환되는 것으로 고른다 (예: Spring Boot 4 / Jackson 3 / JDK 25 호환).

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

**도구**: Flyway. **위치**: `src/main/resources/db/migration/`. **네이밍**: `V{YYYYMMDDHHmmss}__{snake_case_description}.sql` (예: `V20260521143015__add_index_on_wishes_user_id.sql`). KST 기준, 파일을 만들 때의 현 시각을 prefix 로 부여한다 (`date +%Y%m%d%H%M%S`, `HH` 는 24시간).

### 규칙

- **이미 적용된 마이그레이션 파일은 수정·삭제하지 않는다.** Flyway 는 적용 시점에 checksum 을 저장하고, 이후 파일 내용이 바뀌면 다음 부팅에서 실패한다. (삭제는 `ignore-migration-patterns: "*:missing"` 덕에 부팅 자체는 되지만, 신규·CI 환경의 스키마가 운영과 달라진다. 레거시 정리는 `create_init_schema` 로 squash 된 것에 한해 예외다.) 컬럼·제약을 바꿔야 하면 **새 timestamp 로 추가 마이그레이션** 을 작성한다.
- **timestamp 재발급은 불필요하다 — `out-of-order: true`.** 마이그레이션이 전부 additive(순서 무관)이므로 작업 PR 의 timestamp 가 `dev` 최신보다 작아 순서가 어긋나도 Flyway 가 그대로 적용한다. 파일 생성 시각 prefix 를 머지까지 그대로 둔다.
- **마이그레이션은 commutative(순서 무관)하게 유지한다.** `ADD COLUMN` · `CREATE INDEX` · `CREATE TABLE` 같은 additive 는 어느 순서로 적용해도 결과가 같다. 반대로 **순서 의존 변경**(컬럼 rename, 기존 컬럼 `NOT NULL` 화, 같은 컬럼을 두 PR 이 동시 변경, 데이터 `UPDATE` backfill 등)은 out-of-order 에서 적용 순서가 결과를 바꾸므로, 아래 destructive 항목처럼 단계 배포로 분리해 한 배포 사이클 안에서 순서를 보장한다.
- **동시 작업 충돌은 머지 게이트가 잡는다.** branch protection 의 "Require branches to be up to date before merging" 으로, `dev` 가 갱신되면 PR 은 최신 `dev` 와 합쳐 CI 를 다시 통과해야 머지된다. 합쳐서 SQL 이 서로 깨지는 충돌(같은 컬럼 중복 추가 등)은 이 재실행에서 걸린다. 단 CI(빈 DB)는 버전순으로만 적용하므로 "둘 다 SQL 은 성공하나 적용 순서가 결과를 바꾸는" 경우는 못 잡는다 — 그 사각은 위 commutative 규율로 메운다.
- **FK 제약 절대 추가 금지.** (자세한 이유는 `## 테이블 간 외래 키` 섹션) 조회 인덱스(`KEY idx_*`) 는 그대로 둔다.
- **Forward-only.** Flyway down migration / 롤백 SQL 을 작성하지 않는다. 잘못된 마이그레이션을 되돌리려면 **새 timestamp 로 보정 마이그레이션** 을 추가한다.
- **DROP / RENAME 류 destructive 작업은 단계적으로.** 단일 마이그레이션에서 끝내면 (a) 데이터 손실 위험, (b) 배포 중 옛 코드와 새 스키마가 잠시 공존하는 동안 깨진다. 가능한 한 **add → backfill → drop** 3단계로 나눠 배포한다.
- 변경 의도가 한눈에 드러나는 description 을 쓴다.

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

테스트 분류·가치 판단·네이밍·모킹/stub·컨텍스트 캐싱·셋업·기계 강제 등 모든 테스트 규약은 별도 파일에 둔다.

@.claude/rules/testing-convention.md

## DTO ↔ 도메인 매핑

**매핑 로직은 DTO 자신에 둔다. 별도 Mapper 클래스/빈을 만들지 않는다.** "받는 쪽이 매핑을 책임진다" 가 기준:

- **도메인 → 응답 DTO**: 응답 DTO 의 `companion object` 에 `from(도메인)` 정적 팩토리. 예: `UserResponse.from(user)`, `TournamentInfoResponse.from(info)`.
- **요청 DTO → 도메인/커맨드**: 요청 DTO 의 `toXxx()` 인스턴스 메서드. 예: `CreateTournamentRequest.toCreateTournament()`.
- **외부 응답 → 도메인**: 외부 결과 객체의 `toXxx()`. 예: `GeminiExtractionResult.toProductSnapshot(link)`.
- **스냅샷·도메인 → 엔티티**: 받는 엔티티의 `from()`. 예: `Item.from(snapshot)`.

매핑 분기·정규화는 단위 테스트로 검증한다 (`## 테스트 분류` 의 매퍼 함수 분기).

## 컨트롤러 / OpenAPI 문서

**컨트롤러는 `*Api` 인터페이스를 구현한다.** 이 규칙은 **공개 JSON API 엔드포인트**에 적용된다 — 어드민 백오피스(Thymeleaf SSR: `AdminSessionController` · `DiscordAccessController` · `AdminViewController` · `AdminTemplateController` · `AdminAnnouncementController` · `AdminExtractionPolicyController`)와 `HealthController` · `MetricsController`(메트릭 대시보드)는 공개 JSON 응답면이 아니므로 `*Api` 를 구현하지 않으며, 이는 위반이 아니라 정당한 예외다. OpenAPI 어노테이션은 인터페이스, 매핑/검증 어노테이션은 구현체로 분리한다. example 은 평문 JSON 으로 박지 않고 `*ApiExamples` 의 `OperationCustomizer` 빈으로 객체화한다.

### 규칙
- **인터페이스 (`*Api.kt`)**: `@Tag`, `@Operation`, `@ApiResponse(s)`, `@Schema` 만 둔다. 메서드 시그니처는 평범한 함수 (`@PostMapping` 등 매핑 어노테이션 / `@RequestBody` 등 파라미터 어노테이션 / `@Valid` / `@ResponseStatus` 모두 두지 않는다).
- **구현체 (`*Controller.kt`)**: `@RestController`, `@RequestMapping`, 메서드별 `@PostMapping` / `@GetMapping`, 파라미터 어노테이션, `@ResponseStatus` 를 둔다. 라우팅이 컨트롤러만 보면 한눈에 드러나야 한다.
- **example (`*ApiExamples.kt`)**: `@Configuration` + `OperationCustomizer` 빈. 핸들러 매칭은 `HandlerMethod.binds(Controller::method)` (method reference), 응답 코드는 `HttpStatus` enum. path / status 매직 스트링 금지.
- example payload 는 실제 DTO 인스턴스를 만들어 `ApiResponseBody.ok/created/fail` 로 감싸 넘긴다. `@ExampleObject(value = "...JSON 평문...")` 형태 금지 — DTO 시그니처 변경이 컴파일로 추적되지 않는다.
- 새 엔드포인트 추가 / 시그니처 변경 시 인터페이스 + example 빈을 함께 갱신한다. 한쪽만 바꾸면 OpenAPI 문서가 실제 응답과 어긋난다.
- **예외 둘**: `AppleCallbackApi` 는 303 리다이렉트(`ResponseEntity<Void>`)라 `ApiResponseBody` 로 감싸지 않으므로 대응하는 `AppleCallbackApiExamples` 가 없다. `DevAuthApi` 는 별도 `*ApiExamples` 없이 그 example 이 `AuthApiExamples` 에 함께 등록된다.

### 응답 전수 문서화 — 절대 규칙

**`*Api.kt` 의 각 메서드는 멀쩡한 클라이언트가 정상 요청으로 받을 수 있는 모든 응답을 빠짐없이 문서화한다 — 성공 응답과 모든 실패 응답 전부.** 위반을 허용하지 않는 절대 규칙이다. 성공 코드만 달거나 일부 실패를 생략하면 클라이언트가 docs 만 보고 에러 처리를 설계할 수 없다.

**판단 기준은 `## 도메인 예외 정책` 의 그 한 줄과 같다.**
- 닿는다 → 계약 응답 → **문서화 대상**. 성공 2xx · 계약 실패 4xx · 외부 의존성 실패 5xx 전부.
- 못 닿는다 → 서버 버그·불변식 위반(`require` / `check` / `error`, 아래 `handleUnexpected` 의 일반 500) → **문서화 제외**.

조사 대상은 다섯 군데다.

1. **성공 응답** — 정상 흐름의 2xx (200 / 201 등). 컨트롤러의 `@ResponseStatus` 와 일치시킨다.

2. **Spring Security** (`SecurityConfig`) — 엔드포인트에 적용된 권한 설정을 확인한다.
   - `permitAll()` 이 아닌 경우: **401** (미인증)
   - 특정 권한 요구: **403** (권한 없음)

3. **도메인 예외** (`*Exception.kt`) — 서비스·도메인에서 throw 되는 `HttpMappable` 커스텀 예외의 `httpStatus` 를 따른다. 400 / 403 / 404 / 409 등 예외마다 다르므로 실제 throw 지점을 추적한다.

4. **외부 의존성 실패 5xx** — 외부 호출 경계(LLM · HTTP fetch · 결제 등 우리 바깥 의존성)가 던지는 `HttpMappable` 예외. 클라이언트 요청이 정상이어도 우리 밖의 의존성 때문에 떨어지므로 도달 가능한 계약 응답이며, 클라이언트가 재시도 등으로 처리해야 한다. 예: `GeminiApiException` → **502 Bad Gateway** (Gemini 링크 추출 · OCR 실패).

5. **Bean Validation** — 요청 DTO 의 `@NotBlank` · `@Size` 등 위반은 `MethodArgumentNotValidException` → **400** 으로 매핑된다.

**제외 — 일반 500 은 문서화하지 않는다.** `handleUnexpected`(`@ExceptionHandler(Exception::class)`) 가 잡는 `500` 은 예상 못한 서버 버그·불변식 위반이라 정상 요청으로 도달할 수 없고, 모든 엔드포인트 공통이라 엔드포인트별 계약도 아니다. 외부 의존성 실패는 여기 해당하지 않는다 — `HttpMappable` 로 502 등 명시 status 를 던지므로 위 4번 대상이다.

**어노테이션과 example 을 함께 박는다 — 한쪽만 있으면 위반이다.**
- `*Api.kt`: 위 모든 응답을 `@ApiResponse(s)` 로 선언한다. responseCode + 구체적 description.
- `*ApiExamples.kt`: 각 응답에 대응하는 example payload 를 `ApiResponseBody.ok / created / fail` 로 만들어 등록한다. 어노테이션만 있고 example 이 빠진 응답이 없어야 한다.

description 은 구체적으로 쓴다. "오류 등" 같은 모호한 표현 대신 실제 원인을 나열한다.

```kotlin
// 나쁜 예
ApiResponse(responseCode = "400", description = "잘못된 요청 (오류 등)")

// 좋은 예
ApiResponse(responseCode = "400", description = "잘못된 요청 (URL 이 비어 있음 · 유효한 URL 형식이 아님 · https 외 스킴)")
```

새 엔드포인트를 추가하거나 서비스 로직을 변경해 새 예외(특히 새 외부 의존성)가 추가됐다면, `*Api.kt` 의 `@ApiResponses` 와 `*ApiExamples` 를 함께 갱신한다.

### 응답 포맷
**모든 응답은 `ApiResponseBody` 래퍼로 감싼다.** 컨트롤러 메서드는 항상 `ApiResponseBody<T>` 를 반환하고, 직접 `ResponseEntity` / raw DTO 를 노출하지 않는다.

- **예외: 리다이렉트 응답(3xx)은 래퍼로 감싸지 않는다.** 클라이언트가 body 가 아니라 `Location` 헤더를 소비하므로 `ResponseEntity<Void>` 를 직접 반환한다 — 예: `AppleCallbackController` 의 303 리다이렉트.
- 성공 응답은 `ApiResponseBody.ok(...)` / `ApiResponseBody.created(...)`. 실패 응답은 `GlobalExceptionHandler` 가 `ApiResponseBody.fail(...)` 로 매핑한다.
- **HTTP 204 No Content 는 사용하지 않는다.** 래퍼가 항상 body 를 만들기 때문에 RFC 7231 상 "body 없음" 이 본질인 204 와 충돌한다. 내릴 데이터가 없는 응답은 200 OK + `ApiResponseBody.ok()` (data=null) 로 표현한다.
- 비기본 status (`201 CREATED` 등) 는 컨트롤러 메서드에 `@ResponseStatus` 를 명시한다. body 의 `status` 필드와 HTTP 상태 코드를 항상 일치시킨다.

### 이유
- 라우팅(컨트롤러)과 contract(인터페이스)의 관심사를 분리해 컨트롤러가 REST 본질에 집중하게 만든다.
- example 객체화로 DTO 변경이 휴먼 에러로 example 만 어긋나는 함정을 차단한다.
- 일관된 응답 래퍼는 클라이언트 파싱 코드를 단순화하고 status / detail / code 를 한 자리에서 추적 가능하게 한다.

### example 의 fail detail 은 single source 로 — 손으로 박지 않는다

**`*ApiExamples` 의 실패 example `detail` 을 문자열로 직접 박지 않는다.** 손으로 박으면 도메인 예외 message·Bean Validation message 와 같은 문자열이 두 곳에서 따로 놀다가, 한쪽만 바뀌면 docs 가 실제 응답과 어긋나 **거짓말**을 한다 (컴파일러가 못 잡는다). 출처에서 끌어와 single source 로 둔다.

- **도메인 예외 (`HttpMappable`)** → `OperationExamples.add(exception, name)` 오버로드로 등록한다. `exception.httpStatus`·`category`·`message` 에서 status·category·detail 을 자동 추출하며, 이는 `GlobalExceptionHandler.handleBaseException` 의 변환과 **동일**하다. 예외 message·status·category 가 바뀌면 example 이 자동 추종하고, 팩토리 시그니처가 바뀌면 컴파일 에러로 드러난다.
  ```kotlin
  // 금지 — status·category·detail 을 손으로 박음 (예외와 어긋날 수 있음)
  add(status = HttpStatus.NOT_FOUND, name = "...",
      payload = ApiResponseBody.fail<Unit>(ErrorCategory.NOT_FOUND, "존재하지 않는 위시리스트 항목입니다."))
  // 권장 — 예외 하나에서 자동, 컴파일 안전
  add(WishException.notFound(), name = "존재하지 않는 위시 항목")
  ```
  cause 인자가 필요한 팩토리(`ProductLinkException.invalidFormat(cause)` 등)는 더미 cause 를 넘긴다 (헬퍼는 message·category·status 만 쓰므로 payload 에 영향 없음).
- **Bean Validation (`@field` message)** → 메시지를 요청 DTO 의 `companion object const val` 로 빼고, `@field` 와 example 이 **같은 상수**를 참조한다. 실제 응답 detail 은 `GlobalExceptionHandler.detailOf` 가 만드는 `"필드명: 메시지"` 형식이므로 example 도 `"fieldName: ${Dto.MESSAGE_CONST}"` 로 둔다.
- **Security 필터 401/403** (detail 없는 `fail(category)`)은 기존 `unauthorized()`·`forbidden()` 헬퍼를 쓴다.
- example detail 이 실제 응답과 맞는지 불확실하면(특히 Bean Validation 의 `"필드명:"` 접두사 형식) **추측하지 말고 통합테스트의 `$.detail` 단언으로 실측해 고정**한다. 같은 단언이 회귀 방지 contract 도 된다.

응답 detail 의 보안·노이즈, 디버깅 컨텍스트 보존 트레이드오프는 예외 message 정의(`## 도메인 예외 정책` 의 "메시지 톤: 응답 detail 은 전부 사용자 대면, 개발자 구분은 로그로")에서 이미 책임진다. example 은 그 message 를 그대로 끌어다 쓸 뿐이므로 별도 노출 위험을 만들지 않는다.

## PR 생성·갱신

**PR 생성·갱신은 항상 `/pr` 스킬로 한다.** 스킬을 쓸 수 없는 상황이면 수동 `gh` 로 우회하지 말고 사용자에게 먼저 묻는다.
