---
paths: ["src/main/**/*Exception.kt", "src/main/**/*ErrorCode.kt", "src/main/**/GlobalExceptionHandler.kt", "src/main/**/ErrorCategory.kt", "src/main/**/BaseException.kt", "src/main/**/HttpMappable.kt"]
---

# 도메인 예외 (이름 · 생성 · 메시지 톤 · 에러 코드)

`CLAUDE.md` 의 `## 도메인 예외 정책` 이 판단 기준("멀쩡한 클라이언트가 정상 요청으로 여기 닿을 수 있나")과 한 줄 스텁을 갖고, 이 파일이 예외 클래스를 만들거나 message·code 를 만질 때의 상세 규약이다. frontmatter `paths` 의 파일을 다룰 때 자동 로드된다.

## 도메인 예외 이름

도메인 커스텀 예외는 `{도메인 명사}Exception` 으로 짓는다 — `ProductLinkException` · `WishException` · `ProductSnapshotException` · `TournamentException` · `UserException`. 행위명(`...ExtractionException` 등)이 아니라 도메인 용어(명사)를 쓴다.

## 도메인 예외 생성

커스텀 예외는 `*Exception : BaseException, HttpMappable` 패턴이다. 생성자를 `private` 으로 막고 `companion object` 의 **정적 팩토리 메서드**로만 만든다. 각 팩토리는 사유 하나를 나타내며 도메인 `*ErrorCode` 엔트리 하나를 넘긴다. message·category·status 는 그 엔트리에서 파생한다.

```kotlin
class WishException private constructor(
    override val errorCode: ErrorCode,
) : BaseException(errorCode.message),
    HttpMappable {
    override val category: ErrorCategory get() = errorCode.category
    override val httpStatus: HttpStatus get() = errorCode.category.httpStatus

    companion object {
        fun notFound(): WishException = WishException(WishErrorCode.NOT_FOUND)
    }
}
```

호출부는 `throw WishException.notFound()` 처럼 사유 이름만 읽으면 되고, status·메시지는 throw 지점에 흩어지지 않고 `*ErrorCode` 엔트리 한 곳에 모인다.

## 메시지 톤: 응답 detail 은 전부 사용자 대면, 개발자 구분은 로그로

도메인 예외의 message 는 `GlobalExceptionHandler` 를 거쳐 응답 `detail` 로 클라이언트에 그대로 나간다. **누가 어떤 이유로 닿든 사용자가 본다고 가정**하고, status·원인과 무관하게 **모든 detail 은 고정된 사용자 친화 문구**로 둔다. 사용자 입력 검증이든, 앱이 잘못 구성한 프로토콜 필드(OAuth 흐름·`all`/`ids`·provider 경로·상태 param)든, 외부 의존성 실패든 마찬가지다. 예외 message 에 LLM 원문·사용자 입력 원본·내부 식별자·구체적 검증 사유·내부 파라미터 이름·기술 용어 등 민감하거나 내부적인 정보를 담지 않는다. 디버깅에 필요한 구체 정보는 응답이 아니라 로그·cause 체인으로 남긴다.

- 나쁜 예: `invalidValue(reason: String)` — 호출부가 임의 문자열을 message 에 실어 보낼 수 있어, 향후 LLM 원문·입력값이 응답으로 샐 수 있다.
- 좋은 예: 인자 없는 고정 message 팩토리. 사유 구분이 꼭 필요하면 노출돼도 안전한 enum/code 로 받는다.

개발자가 구분·디버깅해야 할 내부 정보(어느 흐름·필드·상태가 잘못됐나, 어느 단계 실패인가)는 **응답이 아니라 로그·cause·메트릭으로 분리**한다.

- **매 요청 단위 디버깅**이 필요하면 던지는 지점에서 로그(레벨은 `CLAUDE.md` 의 `## 로깅` 기준: 클라 계약 위반은 info)나 cause 로 남긴다.
- **분류별 빈도·추세**가 필요하면(예: OAuth 실패가 어느 단계에 몰리나) 매 건 로그가 아니라 메트릭으로 집계한다.
- 앱 구현 버그(둘 다 보냄 같은)는 보통 앱 개발자가 자기 요청으로 알 수 있어, 서버가 응답·로그로 굳이 구분해 줄 필요가 없다. 정말 필요한 구분만 분리한다.

한 줄: **detail 은 사용자에게, 구분은 로그·메트릭에.** 디자이너·기획 문구 카탈로그를 적용할 때 "이건 앱 영역이니 기술 문구로" 같은 예외를 두지 않는다. 그런 구분은 detail 이 아니라 로그가 책임진다.

## 에러 코드 (code 기반 에러 응답)

에러 응답은 사용자 문구가 아니라 **code**(예: `USER-001`)로 사유를 식별한다 — 문구는 클라가 code 로 매핑해 소유한다. 도메인 예외를 만질 때:

- **예외는 도메인 `*ErrorCode` enum 의 코드 하나를 참조**한다 — `code`·`category`·`message` 를 그 엔트리 한 곳에(single source), 팩토리는 코드만 넘긴다.
- **번호는 append-only** — 재사용·재배치 금지, 결번 유지(코드가 클라 계약).
- **status 는 `ErrorCategory` 가 소유**(category → HttpStatus 1:1) — 예외는 직접 안 들고 파생한다.
- **성공 응답은 code 없음**(`null`) — code 는 에러 전용, 성공은 HTTP status + `data`.
- **enum message 는 개발자·문서용 정본**(사용자 최종 문구는 클라 소유) — `detail` 로도 나갈 수 있어 사용자 톤 유지·내부 식별자 금지.
- **`ApiResponseBody.code` 는 `String`**, enum 은 `fail(errorCode)` 경계에서만 받는다(enum 필드는 Jackson 이 이름을 뱉어 어긋남).
- 새 code 는 `@ApiResponse` 설명·전역 카탈로그에 반영.
