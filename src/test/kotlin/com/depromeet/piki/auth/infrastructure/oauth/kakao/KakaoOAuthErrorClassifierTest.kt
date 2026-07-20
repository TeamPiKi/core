package com.depromeet.piki.auth.infrastructure.oauth.kakao

import com.depromeet.piki.common.exception.ErrorCategory
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import java.util.stream.Stream
import kotlin.test.assertEquals

// KakaoOAuthErrorClassifier 의 분기를 망라하는 순수 단위 테스트 (Spring·DB 없음).
// token endpoint 와 user API 는 바디 포맷·분기 필드가 다르므로(전자는 문자열 error_code, 후자는 정수 code)
// 두 경로를 각각 별도 @ParameterizedTest 로 분리한다.
// 우리 설정 오류는 500/SERVER_ERROR(우리 버그), provider 장애는 502/RETRYABLE 로 이제 status 로도 구분되지만,
// 의도(재시도 여부·책임 소재)를 명확히 하려고 category 까지 함께 단언한다.
class KakaoOAuthErrorClassifierTest {
    // token endpoint: { error, error_description, error_code(KOE...) } — 문자열 error_code 로 분기.
    @ParameterizedTest(name = "[{index}] {3} → {1}/{2}")
    @MethodSource("tokenCases")
    fun `Kakao token 에러 응답을 error_code 로 분류한다`(
        status: HttpStatusCode,
        expectedStatus: HttpStatus,
        expectedCategory: ErrorCategory,
        @Suppress("UNUSED_PARAMETER") description: String,
        body: String,
    ) {
        val exception = KakaoOAuthErrorClassifier.classifyTokenError(status, body)

        assertEquals(expectedStatus, exception.httpStatus)
        assertEquals(expectedCategory, exception.category)
    }

    // user API: { msg, code(음수 정수) } — 정수 code 로 분기. msg 는 가변이라 분기에 쓰지 않는다.
    @ParameterizedTest(name = "[{index}] {3} → {1}/{2}")
    @MethodSource("userApiCases")
    fun `Kakao user API 에러 응답을 정수 code 로 분류한다`(
        status: HttpStatusCode,
        expectedStatus: HttpStatus,
        expectedCategory: ErrorCategory,
        @Suppress("UNUSED_PARAMETER") description: String,
        body: String,
    ) {
        val exception = KakaoOAuthErrorClassifier.classifyUserApiError(status, body)

        assertEquals(expectedStatus, exception.httpStatus)
        assertEquals(expectedCategory, exception.category)
    }

    companion object {
        @JvmStatic
        fun tokenCases(): Stream<Arguments> =
            Stream.of(
                // 400 INVALID_INPUT: KOE320 — 인가코드 무효/만료/재사용. 멀쩡한 클라가 정상 요청으로 도달 가능(계약).
                Arguments.of(
                    http(400),
                    HttpStatus.BAD_REQUEST,
                    ErrorCategory.INVALID_INPUT,
                    "KOE320 (invalid_grant) → 400",
                    """{"error":"invalid_grant","error_description":"authorization code not found","error_code":"KOE320"}""",
                ),
                // 500 SERVER_ERROR: 우리 설정 오류(REST 키·secret·redirect 불일치 등). 우리 서버 버그라 500, 재시도 무의미라 SERVER_ERROR.
                Arguments.of(http(400), HttpStatus.INTERNAL_SERVER_ERROR, ErrorCategory.SERVER_ERROR, "KOE101 → 500/SERVER_ERROR", """{"error":"invalid_client","error_code":"KOE101"}"""),
                Arguments.of(http(400), HttpStatus.INTERNAL_SERVER_ERROR, ErrorCategory.SERVER_ERROR, "KOE010 → 500/SERVER_ERROR", """{"error":"invalid_request","error_code":"KOE010"}"""),
                Arguments.of(http(400), HttpStatus.INTERNAL_SERVER_ERROR, ErrorCategory.SERVER_ERROR, "KOE303 → 500/SERVER_ERROR", """{"error":"invalid_request","error_code":"KOE303"}"""),
                Arguments.of(http(400), HttpStatus.INTERNAL_SERVER_ERROR, ErrorCategory.SERVER_ERROR, "KOE114 → 500/SERVER_ERROR", """{"error":"invalid_request","error_code":"KOE114"}"""),
                Arguments.of(http(400), HttpStatus.INTERNAL_SERVER_ERROR, ErrorCategory.SERVER_ERROR, "KOE310 → 500/SERVER_ERROR", """{"error":"invalid_request","error_code":"KOE310"}"""),
                // 502 RETRYABLE: KOE003 — 카카오 OAuth 서버 일시 오류(재시도 대상).
                Arguments.of(http(400), HttpStatus.BAD_GATEWAY, ErrorCategory.RETRYABLE, "KOE003 → 502/RETRYABLE", """{"error":"server_error","error_code":"KOE003"}"""),
                // 502 RETRYABLE fallback — 미지 KOE / error_code 부재 / blank / 파싱 실패.
                Arguments.of(http(400), HttpStatus.BAD_GATEWAY, ErrorCategory.RETRYABLE, "미지의 KOE 코드 → 502/RETRYABLE", """{"error_code":"KOE999"}"""),
                Arguments.of(http(400), HttpStatus.BAD_GATEWAY, ErrorCategory.RETRYABLE, "error_code 부재 → 502/RETRYABLE", """{"error":"x"}"""),
                Arguments.of(http(400), HttpStatus.BAD_GATEWAY, ErrorCategory.RETRYABLE, "error_code 빈 문자열 → 502/RETRYABLE", """{"error_code":""}"""),
                Arguments.of(http(400), HttpStatus.BAD_GATEWAY, ErrorCategory.RETRYABLE, "non-JSON 바디 → 502/RETRYABLE", "<html>Bad Request</html>"),
                Arguments.of(http(400), HttpStatus.BAD_GATEWAY, ErrorCategory.RETRYABLE, "빈 바디 → 502/RETRYABLE", ""),
            )

        @JvmStatic
        fun userApiCases(): Stream<Arguments> =
            Stream.of(
                // 401: -401 — access_token 무효/만료. 멀쩡한 클라가 정상 요청으로 도달 가능(계약).
                Arguments.of(
                    http(401),
                    HttpStatus.UNAUTHORIZED,
                    ErrorCategory.UNAUTHORIZED,
                    "-401 (invalid token) → 401",
                    """{"msg":"this access token does not exist","code":-401}""",
                ),
                // 502 RETRYABLE: provider 장애/점검(재시도 대상). 안정 필드 code 로만 분류(msg 의존 금지).
                Arguments.of(http(400), HttpStatus.BAD_GATEWAY, ErrorCategory.RETRYABLE, "-1 → 502/RETRYABLE", """{"msg":"internal server error","code":-1}"""),
                Arguments.of(http(400), HttpStatus.BAD_GATEWAY, ErrorCategory.RETRYABLE, "-7 → 502/RETRYABLE", """{"msg":"server unavailable","code":-7}"""),
                Arguments.of(http(400), HttpStatus.BAD_GATEWAY, ErrorCategory.RETRYABLE, "-9798 → 502/RETRYABLE", """{"msg":"under maintenance","code":-9798}"""),
                // 500 SERVER_ERROR: 우리 요청 구성 버그(필수 인자 누락·헤더 오류). 우리 서버 버그라 500, 재시도 무의미라 SERVER_ERROR.
                Arguments.of(http(400), HttpStatus.INTERNAL_SERVER_ERROR, ErrorCategory.SERVER_ERROR, "-2 → 500/SERVER_ERROR", """{"msg":"invalid argument","code":-2}"""),
                Arguments.of(http(400), HttpStatus.INTERNAL_SERVER_ERROR, ErrorCategory.SERVER_ERROR, "-8 → 500/SERVER_ERROR", """{"msg":"invalid header","code":-8}"""),
                // 502 RETRYABLE fallback — 미지 음수 code / code 부재 / 정수 아님 / 파싱 실패.
                Arguments.of(http(400), HttpStatus.BAD_GATEWAY, ErrorCategory.RETRYABLE, "미지의 음수 code → 502/RETRYABLE", """{"code":-12345}"""),
                Arguments.of(http(400), HttpStatus.BAD_GATEWAY, ErrorCategory.RETRYABLE, "code 부재 → 502/RETRYABLE", """{"msg":"x"}"""),
                Arguments.of(http(400), HttpStatus.BAD_GATEWAY, ErrorCategory.RETRYABLE, "정수 아닌 code → 502/RETRYABLE", """{"code":"-401"}"""),
                Arguments.of(http(400), HttpStatus.BAD_GATEWAY, ErrorCategory.RETRYABLE, "non-JSON 바디 → 502/RETRYABLE", "Bad Request"),
                Arguments.of(http(400), HttpStatus.BAD_GATEWAY, ErrorCategory.RETRYABLE, "빈 바디 → 502/RETRYABLE", ""),
            )

        private fun http(value: Int): HttpStatusCode = HttpStatusCode.valueOf(value)
    }
}
