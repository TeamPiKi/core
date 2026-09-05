---
paths: ["src/main/**/*Api.kt", "src/main/**/*Controller.kt", "src/main/**/*ApiExamples.kt", "src/main/**/SecurityConfig.kt"]
---

# 컨트롤러 / OpenAPI 문서

`CLAUDE.md` 의 상주 스텁이 핵심 불변식을 갖고, 이 파일이 상세 규약 정본이다. `*Api.kt` · `*Controller.kt` · `*ApiExamples.kt` · `SecurityConfig.kt` 를 다룰 때 자동 로드된다.

**컨트롤러는 `*Api` 인터페이스를 구현한다.** 이 규칙은 **공개 JSON API 엔드포인트**에 적용된다 — 어드민 백오피스(Thymeleaf SSR: `AdminSessionController` · `DiscordAccessController` · `AdminViewController` · `AdminTemplateController` · `AdminAnnouncementController` · `AdminExtractionPolicyController` · `AdminSourcePlatformController`)와 `HealthController` · `MetricsController`(메트릭 대시보드)는 공개 JSON 응답면이 아니므로 `*Api` 를 구현하지 않으며, 이는 위반이 아니라 정당한 예외다. OpenAPI 어노테이션은 인터페이스, 매핑/검증 어노테이션은 구현체로 분리한다. example 은 평문 JSON 으로 박지 않고 `*ApiExamples` 의 `OperationCustomizer` 빈으로 객체화한다.

## 규칙
- **인터페이스 (`*Api.kt`)**: `@Tag`, `@Operation`, `@ApiResponse(s)`, `@Schema` 만 둔다. 메서드 시그니처는 평범한 함수 (`@PostMapping` 등 매핑 어노테이션 / `@RequestBody` 등 파라미터 어노테이션 / `@Valid` / `@ResponseStatus` 모두 두지 않는다).
- **구현체 (`*Controller.kt`)**: `@RestController`, `@RequestMapping`, 메서드별 `@PostMapping` / `@GetMapping`, 파라미터 어노테이션, `@ResponseStatus` 를 둔다. 라우팅이 컨트롤러만 보면 한눈에 드러나야 한다.
- **example (`*ApiExamples.kt`)**: `@Configuration` + `OperationCustomizer` 빈. 핸들러 매칭은 `HandlerMethod.binds(Controller::method)` (method reference), 응답 코드는 `HttpStatus` enum. path / status 매직 스트링 금지.
- example payload 는 실제 DTO 인스턴스를 만들어 `ApiResponseBody.ok/created/fail` 로 감싸 넘긴다. `@ExampleObject(value = "...JSON 평문...")` 형태 금지 — DTO 시그니처 변경이 컴파일로 추적되지 않는다.
- 새 엔드포인트 추가 / 시그니처 변경 시 인터페이스 + example 빈을 함께 갱신한다. 한쪽만 바꾸면 OpenAPI 문서가 실제 응답과 어긋난다.
- **예외 둘**: `AppleCallbackApi` 는 303 리다이렉트(`ResponseEntity<Void>`)라 `ApiResponseBody` 로 감싸지 않으므로 대응하는 `AppleCallbackApiExamples` 가 없다. `DevAuthApi` 는 별도 `*ApiExamples` 없이 그 example 이 `AuthApiExamples` 에 함께 등록된다.

## 응답 전수 문서화

**`*Api.kt` 의 각 메서드는 멀쩡한 클라이언트가 정상 요청으로 받을 수 있는 모든 응답을 빠짐없이 문서화한다 — 성공 응답과 모든 실패 응답 전부.** 성공 코드만 달거나 일부 실패를 생략하면 클라이언트가 docs 만 보고 에러 처리를 설계할 수 없다.

**판단 기준은 `CLAUDE.md` 의 `## 도메인 예외 정책` 의 그 한 줄과 같다.**
- 닿는다 → 계약 응답 → **문서화 대상**. 성공 2xx · 계약 실패 4xx · 외부 의존성 실패 5xx 전부.
- 못 닿는다 → 서버 버그·불변식 위반(`require` / `check` / `error`, 아래 `handleUnexpected` 의 일반 500) → **문서화 제외**.

조사 대상은 다섯 군데다.

1. **성공 응답** — 정상 흐름의 2xx (200 / 201 등). 컨트롤러의 `@ResponseStatus` 와 일치시킨다.

2. **Spring Security** (`SecurityConfig`) — 엔드포인트에 적용된 권한 설정을 확인한다.
   - `permitAll()` 이 아닌 경우: **401** (미인증)
   - 특정 권한 요구: **403** (권한 없음)

3. **도메인 예외** (`*Exception.kt`) — 서비스·도메인에서 throw 되는 `HttpMappable` 커스텀 예외의 `httpStatus` 를 따른다. 400 / 403 / 404 / 409 등 예외마다 다르므로 실제 throw 지점을 추적한다.

4. **외부 의존성 실패 5xx** — 외부 호출 경계(LLM · HTTP fetch · 결제 등 우리 바깥 의존성)가 던지는 `HttpMappable` 예외. 클라이언트 요청이 정상이어도 우리 밖의 의존성 때문에 떨어지므로 도달 가능한 계약 응답이며, 클라이언트가 재시도 등으로 처리해야 한다. 예: `ImageStorageException` → **502 Bad Gateway** (S3 등 외부 스토리지 실패, `RETRYABLE` category 가 502 를 소유).

5. **Bean Validation** — 요청 DTO 의 `@NotBlank` · `@Size` 등 위반은 `MethodArgumentNotValidException` → **400** 으로 매핑된다.

**제외 — 일반 500 은 문서화하지 않는다.** `handleUnexpected`(`@ExceptionHandler(Exception::class)`) 가 잡는 `500` 은 예상 못한 서버 버그·불변식 위반이라 정상 요청으로 도달할 수 없고, 모든 엔드포인트 공통이라 엔드포인트별 계약도 아니다. 외부 의존성 실패는 여기 해당하지 않는다 — `HttpMappable` 로 502 등 명시 status 를 던지므로 위 4번 대상이다.

**어노테이션과 example 을 함께 박는다 — 한쪽만 있으면 위반이다.**
- `*Api.kt`: 위 모든 응답을 `@ApiResponse(s)` 로 선언한다. responseCode + 구체적 description.
- `*ApiExamples.kt`: 각 응답에 대응하는 example payload 를 `ApiResponseBody.ok / created / fail` 로 만들어 등록한다. 어노테이션만 있고 example 이 빠진 응답이 없어야 한다.

**이 절반은 기계가 강제한다 — `OpenApiExampleCoverageIntegrationTest`.** 렌더된 `/v3/api-docs` 를 훑어 "JSON body 를 내리는데 example 이 없는 응답"을 전부 실패로 만든다. 위반 시 example 을 추가하거나, 정말 도달 불가한 응답이면 `@ApiResponse` 선언 자체를 지운다.

반대로 **"도달 가능한 응답이 전부 선언됐는가"는 기계로 못 잡는다** — 호출 그래프를 따라가야 하고 입력 경계가 먼저 거르는 경우(`OAuthException.invalidRequest` 등)를 오탐 없이 가려낼 수 없어 사람 판단이 낀다. 새 예외를 추가하거나 서비스 로직을 바꿀 때 이쪽은 여전히 손으로 챙긴다. 같은 status 안에서 사유 하나가 빠진 것(예: 409 에 example 은 있는데 새 사유만 누락) 역시 이 사각에 들어간다.

description 은 구체적으로 쓴다. "오류 등" 같은 모호한 표현 대신 실제 원인을 나열한다.

```kotlin
// 나쁜 예
ApiResponse(responseCode = "400", description = "잘못된 요청 (오류 등)")

// 좋은 예
ApiResponse(responseCode = "400", description = "잘못된 요청 (URL 이 비어 있음 · 유효한 URL 형식이 아님 · https 외 스킴)")
```

새 엔드포인트를 추가하거나 서비스 로직을 변경해 새 예외(특히 새 외부 의존성)가 추가됐다면, `*Api.kt` 의 `@ApiResponses` 와 `*ApiExamples` 를 함께 갱신한다.

## 응답 포맷
**모든 응답은 `ApiResponseBody` 래퍼로 감싼다.** 컨트롤러 메서드는 항상 `ApiResponseBody<T>` 를 반환하고, 직접 `ResponseEntity` / raw DTO 를 노출하지 않는다.

- **예외: 리다이렉트 응답(3xx)은 래퍼로 감싸지 않는다.** 클라이언트가 body 가 아니라 `Location` 헤더를 소비하므로 `ResponseEntity<Void>` 를 직접 반환한다 — 예: `AppleCallbackController` 의 303 리다이렉트.
- 성공 응답은 `ApiResponseBody.ok(...)` / `ApiResponseBody.created(...)`. 실패 응답은 `GlobalExceptionHandler` 가 `ApiResponseBody.fail(...)` 로 매핑한다.
- **HTTP 204 No Content 는 사용하지 않는다.** 래퍼가 항상 body 를 만들기 때문에 RFC 7231 상 "body 없음" 이 본질인 204 와 충돌한다. 내릴 데이터가 없는 응답은 200 OK + `ApiResponseBody.ok()` (data=null) 로 표현한다.
- 비기본 status (`201 CREATED` 등) 는 컨트롤러 메서드에 `@ResponseStatus` 를 명시한다. body 의 `status` 필드와 HTTP 상태 코드를 항상 일치시킨다.

## 이유
- 라우팅(컨트롤러)과 contract(인터페이스)의 관심사를 분리해 컨트롤러가 REST 본질에 집중하게 만든다.
- example 객체화로 DTO 변경이 휴먼 에러로 example 만 어긋나는 함정을 차단한다.
- 일관된 응답 래퍼는 클라이언트 파싱 코드를 단순화하고 status / detail / code 를 한 자리에서 추적 가능하게 한다.

## example 의 fail detail 은 single source 로 — 손으로 박지 않는다

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

응답 detail 의 보안·노이즈, 디버깅 컨텍스트 보존 트레이드오프는 예외 message 정의(`CLAUDE.md` 의 `## 도메인 예외 정책` 의 "메시지 톤: 응답 detail 은 전부 사용자 대면, 개발자 구분은 로그로")에서 이미 책임진다. example 은 그 message 를 그대로 끌어다 쓸 뿐이므로 별도 노출 위험을 만들지 않는다.
