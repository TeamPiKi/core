package com.depromeet.piki.auth.infrastructure.oauth.apple

import com.depromeet.piki.common.exception.ErrorCategory
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import java.util.stream.Stream
import kotlin.test.assertEquals

// AppleOAuthErrorClassifier 의 분기를 망라하는 순수 단위 테스트 (Spring·DB 없음).
// PLAN.md 의 실제 Apple ErrorResponse 샘플({"error":"..."} 단일 필드)을 픽스처로 쓴다.
// 우리 설정 오류는 500/SERVER_ERROR(우리 버그), provider 장애는 502/RETRYABLE 로 이제 status 로도 구분되지만,
// 의도(재시도 여부·책임 소재)를 명확히 하려고 category 까지 함께 단언한다.
class AppleOAuthErrorClassifierTest {
    @ParameterizedTest(name = "[{index}] {3} → {1}/{2}")
    @MethodSource("cases")
    fun `Apple token 에러 응답을 status·body 로 분류한다`(
        status: HttpStatusCode,
        expectedStatus: HttpStatus,
        expectedCategory: ErrorCategory,
        @Suppress("UNUSED_PARAMETER") description: String,
        body: String,
    ) {
        val cause = RuntimeException("apple token exchange failed")

        val exception = AppleOAuthErrorClassifier.classify(status, body, cause)

        assertEquals(expectedStatus, exception.httpStatus)
        assertEquals(expectedCategory, exception.category)
    }

    companion object {
        @JvmStatic
        fun cases(): Stream<Arguments> =
            Stream.of(
                // 400 INVALID_INPUT: code 만료(5분)/재사용 — 멀쩡한 클라가 정상 요청으로 도달 가능(계약). 보수 매핑.
                Arguments.of(http(400), HttpStatus.BAD_REQUEST, ErrorCategory.INVALID_INPUT, "invalid_grant → 400", """{"error":"invalid_grant"}"""),
                // 500 SERVER_ERROR: 우리 client_secret JWT·요청 구성 오류(우리 설정 버그). 우리 서버 버그라 500, 재시도 무의미라 SERVER_ERROR.
                Arguments.of(http(400), HttpStatus.INTERNAL_SERVER_ERROR, ErrorCategory.SERVER_ERROR, "invalid_client → 500/SERVER_ERROR", """{"error":"invalid_client"}"""),
                Arguments.of(http(400), HttpStatus.INTERNAL_SERVER_ERROR, ErrorCategory.SERVER_ERROR, "invalid_request → 500/SERVER_ERROR", """{"error":"invalid_request"}"""),
                Arguments.of(http(400), HttpStatus.INTERNAL_SERVER_ERROR, ErrorCategory.SERVER_ERROR, "unauthorized_client → 500/SERVER_ERROR", """{"error":"unauthorized_client"}"""),
                Arguments.of(
                    http(400),
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    ErrorCategory.SERVER_ERROR,
                    "unsupported_grant_type → 500/SERVER_ERROR",
                    """{"error":"unsupported_grant_type"}""",
                ),
                Arguments.of(http(400), HttpStatus.INTERNAL_SERVER_ERROR, ErrorCategory.SERVER_ERROR, "invalid_scope → 500/SERVER_ERROR", """{"error":"invalid_scope"}"""),
                // error_description 이 동봉돼도 안정 필드인 error 코드로만 분류한다 (메시지 의존 금지).
                Arguments.of(
                    http(400),
                    HttpStatus.BAD_REQUEST,
                    ErrorCategory.INVALID_INPUT,
                    "invalid_grant + error_description 동봉 → 400",
                    """{"error":"invalid_grant","error_description":"code expired"}""",
                ),
                // 502 RETRYABLE fallback — 미지의 error 코드 / error 필드 부재 / 파싱 실패 / HTTP≠400(Apple outage).
                Arguments.of(http(400), HttpStatus.BAD_GATEWAY, ErrorCategory.RETRYABLE, "미지의 error 코드 → 502/RETRYABLE", """{"error":"teapot"}"""),
                Arguments.of(http(400), HttpStatus.BAD_GATEWAY, ErrorCategory.RETRYABLE, "error 필드 부재 → 502/RETRYABLE", """{"foo":"bar"}"""),
                Arguments.of(http(400), HttpStatus.BAD_GATEWAY, ErrorCategory.RETRYABLE, "non-JSON 바디 → 502/RETRYABLE", "<html>Bad Request</html>"),
                Arguments.of(http(400), HttpStatus.BAD_GATEWAY, ErrorCategory.RETRYABLE, "빈 바디 → 502/RETRYABLE", ""),
                // HTTP≠400 — Apple outage·장애. 401 케이스는 Apple token endpoint 엔 없으나 방어적으로 502 매핑 확인.
                Arguments.of(http(401), HttpStatus.BAD_GATEWAY, ErrorCategory.RETRYABLE, "HTTP 401 → 502/RETRYABLE", """{"error":"invalid_client"}"""),
                Arguments.of(http(500), HttpStatus.BAD_GATEWAY, ErrorCategory.RETRYABLE, "HTTP 500 → 502/RETRYABLE", "Internal Server Error"),
                Arguments.of(http(503), HttpStatus.BAD_GATEWAY, ErrorCategory.RETRYABLE, "HTTP 503 → 502/RETRYABLE", "Service Unavailable"),
            )

        private fun http(value: Int): HttpStatusCode = HttpStatusCode.valueOf(value)
    }
}
