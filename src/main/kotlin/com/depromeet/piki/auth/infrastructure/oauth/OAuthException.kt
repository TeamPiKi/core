package com.depromeet.piki.auth.infrastructure.oauth

import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode
import com.depromeet.piki.common.exception.HttpMappable
import org.springframework.http.HttpStatus

// message·category·httpStatus 는 전부 errorCode 하나에서 파생한다(OAuthErrorCode 가 single source).
// 사유가 달라도 사용자 문구가 같을 수 있으나(예: providerError·misconfigured), code 로 구분되고 어느 사유였는지는
// 던지는 지점 로그로 남긴다. 디버깅용 원인은 cause 체인으로만 보존(원문 비노출).
class OAuthException private constructor(
    override val errorCode: ErrorCode,
    cause: Throwable? = null,
) : BaseException(errorCode.message, cause),
    HttpMappable {
    override val category: ErrorCategory get() = errorCode.category
    override val httpStatus: HttpStatus get() = errorCode.category.httpStatus

    companion object {
        // 소셜 제공자(Kakao/Google) 호출 실패 — 우리 밖 의존성. 정상 요청이어도 도달 가능한 계약 → 502(RETRYABLE).
        fun providerError(cause: Throwable): OAuthException = OAuthException(OAuthErrorCode.PROVIDER_ERROR, cause)

        // code(+redirectUri) 도 accessToken 도 없어 어느 흐름도 성립 안 함 → 400 (validFlow 의 service 중복방어).
        fun invalidRequest(): OAuthException = OAuthException(OAuthErrorCode.INVALID_REQUEST)

        // 지원하지 않는 provider (미구현 apple · 오타 등) → 400.
        fun unsupportedProvider(): OAuthException = OAuthException(OAuthErrorCode.UNSUPPORTED_PROVIDER)

        // state 없음 · 만료 · 이미 소비됨 → 401. CSRF 방지용 state 불일치로 요청을 거부.
        fun invalidState(): OAuthException = OAuthException(OAuthErrorCode.INVALID_STATE)

        // provider 인가코드(code)가 만료/재사용/무효 — 멀쩡한 클라가 정상 요청으로 도달 가능(계약) → 400.
        fun invalidGrant(): OAuthException = OAuthException(OAuthErrorCode.INVALID_GRANT)

        // provider access token 무효/만료 — 클라가 정상 요청으로 도달 가능(계약) → 401.
        fun invalidProviderToken(): OAuthException = OAuthException(OAuthErrorCode.INVALID_PROVIDER_TOKEN)

        // 우리 OAuth 설정/요청 오류(client_id/secret · client_secret JWT · scope · 필수 인자 누락 등)를 provider 가 거부.
        // 상류 장애가 아니라 우리 서버 버그 → SERVER_ERROR(500), 재시도 무의미(알림 신호). 원인은 cause 로만 보존.
        fun misconfigured(cause: Throwable): OAuthException = OAuthException(OAuthErrorCode.MISCONFIGURED, cause)
    }
}
