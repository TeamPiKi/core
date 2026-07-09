package com.depromeet.piki.auth.infrastructure.oauth

import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.HttpMappable
import org.springframework.http.HttpStatus

class OAuthException private constructor(
    message: String,
    override val category: ErrorCategory,
    override val httpStatus: HttpStatus,
    cause: Throwable? = null,
) : BaseException(message, cause),
    HttpMappable {
    companion object {
        // 응답 detail 의 single source. 여러 사유가 사용자에겐 같은 안내라 문구를 공유한다.
        // (어느 사유였는지는 응답이 아니라 던지는 지점 로그로 구분한다.)
        private const val LOGIN_FAILED_RETRY_LATER = "로그인에 실패했어요. 잠시 후 다시 시도해 주세요."
        private const val SESSION_EXPIRED_RETRY = "로그인 정보가 만료됐어요. 다시 시도해 주세요."

        // 소셜 제공자(Kakao/Google) 호출 실패 — 우리 밖 의존성. 정상 요청이어도 도달 가능한 계약 → 502.
        // 디버깅용 원인은 cause 로만 남기고 message 는 고정 문구(원문 노출 금지).
        fun providerError(cause: Throwable): OAuthException =
            OAuthException(LOGIN_FAILED_RETRY_LATER, ErrorCategory.RETRYABLE, HttpStatus.BAD_GATEWAY, cause)

        // code(+redirectUri) 도 accessToken 도 없어 어느 흐름도 성립 안 함 → 400 (validFlow 의 service 중복방어).
        // 응답 detail 은 사용자 대면이라 친화 문구로 둔다. 어느 흐름이 잘못됐는지는 응답이 아니라 로그로 남긴다.
        fun invalidRequest(): OAuthException =
            OAuthException(
                "로그인에 실패했어요. 다시 시도해 주세요.",
                ErrorCategory.INVALID_INPUT,
                HttpStatus.BAD_REQUEST,
            )

        // 지원하지 않는 provider (미구현 apple · 오타 등) → 400.
        fun unsupportedProvider(): OAuthException =
            OAuthException("지원하지 않는 로그인 방식이에요.", ErrorCategory.INVALID_INPUT, HttpStatus.BAD_REQUEST)

        // state 없음 · 만료 · 이미 소비됨 → 401. CSRF 방지용 state 불일치로 요청을 거부.
        fun invalidState(): OAuthException =
            OAuthException(SESSION_EXPIRED_RETRY, ErrorCategory.UNAUTHORIZED, HttpStatus.UNAUTHORIZED)

        // provider 인가코드(code)가 만료/재사용/무효 — 멀쩡한 클라가 정상 요청으로 도달 가능(계약) → 400.
        // 재로그인으로 새 code 를 받아 재시도하면 해소된다. 원문은 cause 로만, message 는 고정 문구.
        fun invalidGrant(): OAuthException =
            OAuthException(SESSION_EXPIRED_RETRY, ErrorCategory.INVALID_INPUT, HttpStatus.BAD_REQUEST)

        // provider access token 무효/만료 — 클라가 정상 요청으로 도달 가능(계약) → 401.
        // 재로그인으로 새 토큰을 받아야 해소된다. 원문은 cause 로만, message 는 고정 문구.
        fun invalidProviderToken(): OAuthException =
            OAuthException(
                "로그인 정보가 만료됐어요. 다시 로그인해 주세요.",
                ErrorCategory.UNAUTHORIZED,
                HttpStatus.UNAUTHORIZED,
            )

        // 우리 OAuth 설정/요청 오류(client_id/secret · client_secret JWT · 필수 인자 누락 등) — 외부 호출 경계에서
        // provider 가 "네 자격/요청이 틀렸다"고 거부한 것 — 외부 호출 경계 실패라 502 + SERVER_ERROR 로 매핑한다:
        // status 502 = 외부 호출 경계 실패, category SERVER_ERROR = 재시도해도 무의미한 우리 서버 문제(알림 신호).
        // 정상 클라 요청으로 해소 불가. 원인은 cause 로만 보존하고 message 는 고정 문구(원문 비노출).
        fun misconfigured(cause: Throwable): OAuthException =
            OAuthException(
                LOGIN_FAILED_RETRY_LATER,
                ErrorCategory.SERVER_ERROR,
                HttpStatus.BAD_GATEWAY,
                cause,
            )
    }
}
