package com.depromeet.piki.auth.infrastructure.oauth.apple

import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode
import com.depromeet.piki.common.exception.HttpMappable
import org.springframework.http.HttpStatus

// Apple 서버-서버 알림 처리 예외. 이 엔드포인트는 우리 JWT 인증 없이(permitAll) 열려 있고,
// 진위는 오직 payload JWT 의 서명으로 가린다. 서명·issuer·aud 검증이 깨지거나 payload 가 우리가
// 아는 형식이 아니면 = Apple 이 보낸 정상 알림이 아니므로 401 로 거부한다 (위조 호출 차단의 핵심 방어선).
// message·category·httpStatus 는 전부 errorCode 하나에서 파생한다(AppleErrorCode 가 single source).
// 디버깅용 원인은 cause 로만 남기고 message 는 고정 문구로 둔다(원문 비노출).
class AppleNotificationException private constructor(
    override val errorCode: ErrorCode,
    cause: Throwable? = null,
) : BaseException(errorCode.message, cause),
    HttpMappable {
    override val category: ErrorCategory get() = errorCode.category
    override val httpStatus: HttpStatus get() = errorCode.category.httpStatus

    companion object {
        // 서명/issuer/aud 검증 실패 또는 payload 형식 오류 — Apple 이 보낸 게 아니거나 위조된 호출 → 401.
        fun invalidSignature(cause: Throwable? = null): AppleNotificationException =
            AppleNotificationException(AppleErrorCode.INVALID_SIGNATURE, cause)

        // 서명 검증에 필요한 Apple JWKS(/auth/keys) 조회 실패 — 우리 밖 의존성 장애라 위조와 구분해 502 로 올린다.
        fun providerError(cause: Throwable): AppleNotificationException =
            AppleNotificationException(AppleErrorCode.PROVIDER_ERROR, cause)
    }
}
