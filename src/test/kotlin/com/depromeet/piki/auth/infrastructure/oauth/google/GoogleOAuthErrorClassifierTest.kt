package com.depromeet.piki.auth.infrastructure.oauth.google

import com.depromeet.piki.common.exception.ErrorCategory
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.http.HttpStatus
import java.util.stream.Stream
import kotlin.test.assertEquals

// GoogleOAuthErrorClassifier 의 분기를 망라하는 순수 단위 테스트 (Spring·DB 없음).
// TOKEN endpoint 는 안정 바디 필드(`error` 코드)로, USER_INFO endpoint 는 HTTP status 로 분기한다.
// 우리 설정 오류(invalid_client 등)는 500/SERVER_ERROR(우리 버그), provider 장애는 502/RETRYABLE 로 이제 status 로도
// 구분되지만, 의도(재시도 여부·책임 소재)를 명확히 하려고 category 까지 함께 단언한다.
class GoogleOAuthErrorClassifierTest {
    // TOKEN: HTTP status 가 400/401 로 혼재 가능 → 안정 `error` 코드로만 분류한다. statusCode 는 무의미해 0 으로 고정.
    @ParameterizedTest(name = "[{index}] {2} → {0}/{1}")
    @MethodSource("tokenCases")
    fun `Google token 에러 응답을 안정 error 코드로 분류한다`(
        expectedStatus: HttpStatus,
        expectedCategory: ErrorCategory,
        @Suppress("UNUSED_PARAMETER") description: String,
        body: String,
    ) {
        val cause = RuntimeException("google token exchange failed")

        val exception = GoogleOAuthErrorClassifier.classify(GoogleOAuthEndpoint.TOKEN, statusCode = 0, body = body, cause = cause)

        assertEquals(expectedStatus, exception.httpStatus)
        assertEquals(expectedCategory, exception.category)
    }

    // USER_INFO: access_token 무효/만료는 HTTP 401 로 온다 — status 401 → 401, 403(우리 권한/설정) → 500, 5xx(provider 장애) → 502.
    @ParameterizedTest(name = "[{index}] {2} → {0}/{1}")
    @MethodSource("userInfoCases")
    fun `Google userinfo 에러 응답을 HTTP status 로 분류한다`(
        expectedStatus: HttpStatus,
        expectedCategory: ErrorCategory,
        @Suppress("UNUSED_PARAMETER") description: String,
        statusCode: Int,
    ) {
        val cause = RuntimeException("google userinfo failed")

        val exception = GoogleOAuthErrorClassifier.classify(GoogleOAuthEndpoint.USER_INFO, statusCode = statusCode, body = "", cause = cause)

        assertEquals(expectedStatus, exception.httpStatus)
        assertEquals(expectedCategory, exception.category)
    }

    companion object {
        @JvmStatic
        fun tokenCases(): Stream<Arguments> =
            Stream.of(
                // 400 INVALID_INPUT: 멀쩡한 클라가 정상 요청으로 도달 가능(계약). code 만료/재사용·요청 형식 오류.
                Arguments.of(HttpStatus.BAD_REQUEST, ErrorCategory.INVALID_INPUT, "invalid_grant → 400", """{"error":"invalid_grant"}"""),
                Arguments.of(HttpStatus.BAD_REQUEST, ErrorCategory.INVALID_INPUT, "invalid_request → 400", """{"error":"invalid_request"}"""),
                // 500 SERVER_ERROR: 우리 OAuth 설정 오류(우리 버그). 상류 장애가 아니라 우리 서버 문제라 500, 재시도 무의미라 SERVER_ERROR.
                Arguments.of(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCategory.SERVER_ERROR, "invalid_client → 500/SERVER_ERROR", """{"error":"invalid_client"}"""),
                Arguments.of(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    ErrorCategory.SERVER_ERROR,
                    "unauthorized_client → 500/SERVER_ERROR",
                    """{"error":"unauthorized_client"}""",
                ),
                Arguments.of(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    ErrorCategory.SERVER_ERROR,
                    "unsupported_grant_type → 500/SERVER_ERROR",
                    """{"error":"unsupported_grant_type"}""",
                ),
                Arguments.of(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCategory.SERVER_ERROR, "invalid_scope → 500/SERVER_ERROR", """{"error":"invalid_scope"}"""),
                // error_description 이 동봉돼도 안정 필드인 error 코드로만 분류한다 (메시지 의존 금지).
                Arguments.of(
                    HttpStatus.BAD_REQUEST,
                    ErrorCategory.INVALID_INPUT,
                    "invalid_grant + error_description 동봉 → 400",
                    """{"error":"invalid_grant","error_description":"Bad Request"}""",
                ),
                // 502 RETRYABLE fallback — 미지의 error 코드 / 파싱 실패. provider 장애로 보고 재시도 대상.
                Arguments.of(HttpStatus.BAD_GATEWAY, ErrorCategory.RETRYABLE, "미지의 error 코드 → 502/RETRYABLE", """{"error":"teapot"}"""),
                Arguments.of(HttpStatus.BAD_GATEWAY, ErrorCategory.RETRYABLE, "error 필드 부재 → 502/RETRYABLE", """{"foo":"bar"}"""),
                Arguments.of(HttpStatus.BAD_GATEWAY, ErrorCategory.RETRYABLE, "non-JSON 바디 → 502/RETRYABLE", "<html>Bad Request</html>"),
                Arguments.of(HttpStatus.BAD_GATEWAY, ErrorCategory.RETRYABLE, "빈 바디 → 502/RETRYABLE", ""),
            )

        @JvmStatic
        fun userInfoCases(): Stream<Arguments> =
            Stream.of(
                // 401: access_token 무효/만료 — 클라가 정상 요청으로 도달 가능(계약).
                Arguments.of(HttpStatus.UNAUTHORIZED, ErrorCategory.UNAUTHORIZED, "HTTP 401 → 401", 401),
                // 403(scope 부족 등 우리 권한/설정 문제) → 500/SERVER_ERROR (재시도 무의미). 5xx 는 provider 장애 → RETRYABLE.
                Arguments.of(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCategory.SERVER_ERROR, "HTTP 403 → 500/SERVER_ERROR", 403),
                Arguments.of(HttpStatus.BAD_GATEWAY, ErrorCategory.RETRYABLE, "HTTP 500 → 502/RETRYABLE", 500),
                Arguments.of(HttpStatus.BAD_GATEWAY, ErrorCategory.RETRYABLE, "HTTP 503 → 502/RETRYABLE", 503),
            )
    }
}
