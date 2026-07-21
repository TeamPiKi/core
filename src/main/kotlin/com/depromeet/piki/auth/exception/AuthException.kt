package com.depromeet.piki.auth.exception

import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode
import com.depromeet.piki.common.exception.HttpMappable
import org.springframework.http.HttpStatus

// message·category·httpStatus 는 전부 errorCode 하나에서 파생한다(AuthErrorCode 가 single source).
class AuthException private constructor(
    override val errorCode: ErrorCode,
) : BaseException(errorCode.message),
    HttpMappable {
    override val category: ErrorCategory get() = errorCode.category
    override val httpStatus: HttpStatus get() = errorCode.category.httpStatus

    companion object {
        fun invalidToken(): AuthException = AuthException(AuthErrorCode.INVALID_TOKEN)

        fun missingNickname(): AuthException = AuthException(AuthErrorCode.MISSING_NICKNAME)

        // refresh 토큰이 쿠키·body 어느 쪽에도 없을 때. 정상 클라이언트가 도달 가능한 계약 → 400.
        fun refreshTokenRequired(): AuthException = AuthException(AuthErrorCode.REFRESH_TOKEN_REQUIRED)
    }
}
