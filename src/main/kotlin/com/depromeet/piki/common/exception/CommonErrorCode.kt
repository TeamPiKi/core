package com.depromeet.piki.common.exception

// 횡단(공통) 에러의 code 배정표(에픽 #728). 특정 도메인에 속하지 않는 Security 401/403·표준 MVC 4xx·
// Bean Validation 400·generic 500·외부 의존성 5xx 가 참조한다. 번호는 append-only — 재배치·결번 침범 금지.
//
// 4xx 는 사유별 구체 code, 5xx 는 도메인별 code 를 두지 않고 재시도 방식별 3개로 뭉친다 —
// 5xx 에서 클라가 취할 유일한 행동이 "어떻게 재시도할지" 뿐이라, code 자체가 재시도 신호이기 때문이다.
//   RETRYABLE(502)   — 즉시 재시도 가능(외부 의존성 일시 장애)
//   SERVER_BUSY(503) — Retry-After 만큼 대기 후 재시도(load shedding, 자리만 정의: 실제 발생은 후속 이슈)
//   SERVER_ERROR(500·502) — 재시도 무의미, 백엔드가 고쳐야 함(우리 설정·코드 버그). 502 는 외부 호출 경계
//     실패지만 재시도해도 무의미한 경우(OAuth misconfigured 등)라 status 는 502·500 둘 다 올 수 있다.
//     5xx 는 status 가 아니라 code 로 재시도 방식을 읽으므로 status 다중은 계약에 영향 없다.
//
// code·category·message 를 한 엔트리에 모아 single source 로 둔다: status 는 category.httpStatus 로,
// 응답 detail·로그·OpenAPI 문서·코드 카탈로그는 이 정의에서 파생된다.
//
// 도메인 code(USER-001 등)는 숫자 append-only 지만, 공통 code 는 **의미 문자열**을 wire code 로 쓴다(FE 에 이미
// 배포된 매핑 문서의 계약). 특히 5xx 3종은 code 이름 자체가 클라의 재시도 신호(RETRYABLE/SERVER-BUSY/SERVER-ERROR)라
// 의미 문자열이 자연스럽다. code 문자열은 클라 계약이므로 **이름 변경·재배치 금지**(추가만 허용).
enum class CommonErrorCode(
    override val code: String,
    override val category: ErrorCategory,
    override val message: String,
) : ErrorCode {
    UNAUTHORIZED("COMMON-UNAUTHORIZED", ErrorCategory.UNAUTHORIZED, "로그인이 필요해요."),
    FORBIDDEN("COMMON-FORBIDDEN", ErrorCategory.FORBIDDEN, "접근 권한이 없어요."),
    NOT_FOUND("COMMON-NOT-FOUND", ErrorCategory.NOT_FOUND, "요청하신 정보를 찾을 수 없어요."),
    METHOD_NOT_ALLOWED("COMMON-METHOD-NOT-ALLOWED", ErrorCategory.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식이에요."),
    UNSUPPORTED_MEDIA_TYPE("COMMON-UNSUPPORTED-MEDIA-TYPE", ErrorCategory.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 형식이에요."),
    INVALID_INPUT("COMMON-INVALID-INPUT", ErrorCategory.INVALID_INPUT, "요청 값을 다시 확인해 주세요."),
    TOO_MANY_REQUESTS("COMMON-TOO-MANY-REQUESTS", ErrorCategory.TOO_MANY_REQUESTS, "요청이 너무 많아요. 잠시 후 다시 시도해 주세요."),
    RETRYABLE("COMMON-RETRYABLE", ErrorCategory.RETRYABLE, "일시적인 오류예요. 잠시 후 다시 시도해 주세요."),
    SERVER_BUSY("COMMON-SERVER-BUSY", ErrorCategory.SERVER_BUSY, "지금 요청이 많아요. 잠시 후 다시 시도해 주세요."),
    SERVER_ERROR("COMMON-SERVER-ERROR", ErrorCategory.SERVER_ERROR, "서버에 문제가 발생했어요. 불편을 드려 죄송해요."),
    ;

    companion object {
        // category → 공통 code 는 1:1 (각 엔트리가 서로 다른 category 를 가진다). CONFLICT 는 항상 도메인 사유라
        // 공통 code 가 없어 null 이 나오며, 그 경우 호출부는 기존 fail(category) fallback(code 없음)으로 되돌린다.
        private val byCategory: Map<ErrorCategory, CommonErrorCode> = entries.associateBy { it.category }

        fun of(category: ErrorCategory): CommonErrorCode? = byCategory[category]
    }
}
