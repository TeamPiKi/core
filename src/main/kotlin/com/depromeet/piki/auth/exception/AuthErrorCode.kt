package com.depromeet.piki.auth.exception

import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode

// AuthException 의 code 배정표(에픽 #728). 우리 JWT/세션 인증 계약 위반을 다룬다.
// 번호는 append-only — 재배치·결번 침범 금지. code·category·message 를 한 엔트리에 모아 single source 로 둔다:
// status 는 category.httpStatus 로, 응답 detail·로그·OpenAPI 문서는 message 로 파생된다.
enum class AuthErrorCode(
    override val code: String,
    override val category: ErrorCategory,
    override val message: String,
) : ErrorCode {
    INVALID_TOKEN("AUTH-001", ErrorCategory.UNAUTHORIZED, "로그인 정보가 만료됐어요. 다시 로그인해 주세요."),
    MISSING_NICKNAME("AUTH-002", ErrorCategory.INVALID_INPUT, "닉네임을 입력해 주세요."),
    REFRESH_TOKEN_REQUIRED("AUTH-003", ErrorCategory.INVALID_INPUT, "다시 로그인해 주세요."),
}
