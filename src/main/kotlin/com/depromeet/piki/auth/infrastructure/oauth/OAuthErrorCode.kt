package com.depromeet.piki.auth.infrastructure.oauth

import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode

// OAuthException 의 code 배정표(에픽 #728). 소셜 provider(Kakao·Google·Apple) 연동 실패를 다룬다.
// 번호는 append-only. code·category·message 를 한 엔트리에 모아 single source 로 둔다:
// status 는 category.httpStatus 로 파생된다.
//
// MISCONFIGURED 는 우리 OAuth 설정/요청 오류(client_id/secret·redirect_uri·scope 등)를 provider 가 거부한 것 —
// 상류(gateway) 장애가 아니라 우리 서버 버그이므로 SERVER_ERROR(500)로 둔다. (구현 시점엔 502 였으나 category 로
// status 를 파생하며 500 으로 교정. RETRYABLE(provider 일시 장애, 502)과 status 로도 구분된다.)
enum class OAuthErrorCode(
    override val code: String,
    override val category: ErrorCategory,
    override val message: String,
) : ErrorCode {
    PROVIDER_ERROR("OAUTH-001", ErrorCategory.RETRYABLE, "로그인에 실패했어요. 잠시 후 다시 시도해 주세요."),
    INVALID_REQUEST("OAUTH-002", ErrorCategory.INVALID_INPUT, "로그인에 실패했어요. 다시 시도해 주세요."),
    UNSUPPORTED_PROVIDER("OAUTH-003", ErrorCategory.INVALID_INPUT, "지원하지 않는 로그인 방식이에요."),
    INVALID_STATE("OAUTH-004", ErrorCategory.UNAUTHORIZED, "로그인 정보가 만료됐어요. 다시 시도해 주세요."),
    INVALID_GRANT("OAUTH-005", ErrorCategory.INVALID_INPUT, "로그인 정보가 만료됐어요. 다시 시도해 주세요."),
    INVALID_PROVIDER_TOKEN("OAUTH-006", ErrorCategory.UNAUTHORIZED, "로그인 정보가 만료됐어요. 다시 로그인해 주세요."),
    MISCONFIGURED("OAUTH-007", ErrorCategory.SERVER_ERROR, "로그인에 실패했어요. 잠시 후 다시 시도해 주세요."),
}
