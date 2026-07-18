package com.depromeet.piki.common.exception

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.http.HttpStatus
import kotlin.test.assertEquals

// handleExceptionInternal 이 표준 MVC 예외 status 를 우리 ErrorCategory 로 옮기는 categoryOf 의 분기를 단위로 고정한다.
// 특히 5xx 는 status 와 code 의 재시도 계약이 어긋나면 안 된다 — 502→RETRYABLE, 503→SERVER_BUSY, 그 외 5xx→SERVER_ERROR.
// (AsyncRequestTimeout→503, ResponseStatusException(502/503) 이 이 경로로 들어올 수 있어 방어한다.)
class GlobalExceptionHandlerCategoryTest {
    private val handler = GlobalExceptionHandler()

    @ParameterizedTest
    @CsvSource(
        "UNAUTHORIZED, UNAUTHORIZED",
        "FORBIDDEN, FORBIDDEN",
        "NOT_FOUND, NOT_FOUND",
        "METHOD_NOT_ALLOWED, METHOD_NOT_ALLOWED",
        "UNSUPPORTED_MEDIA_TYPE, UNSUPPORTED_MEDIA_TYPE",
        "CONFLICT, CONFLICT",
        "BAD_REQUEST, INVALID_INPUT",
        "PAYLOAD_TOO_LARGE, INVALID_INPUT",
        "BAD_GATEWAY, RETRYABLE",
        "SERVICE_UNAVAILABLE, SERVER_BUSY",
        "INTERNAL_SERVER_ERROR, SERVER_ERROR",
        "GATEWAY_TIMEOUT, SERVER_ERROR",
    )
    fun `categoryOf 는 status 를 알맞은 ErrorCategory 로 옮긴다`(
        status: HttpStatus,
        expected: ErrorCategory,
    ) {
        assertEquals(expected, handler.categoryOf(status))
    }

    @ParameterizedTest
    @CsvSource(
        "BAD_GATEWAY, COMMON-RETRYABLE",
        "SERVICE_UNAVAILABLE, COMMON-SERVER-BUSY",
        "INTERNAL_SERVER_ERROR, COMMON-SERVER-ERROR",
    )
    fun `5xx 는 status 와 어긋나지 않는 재시도 방식별 공통 code 로 파생된다`(
        status: HttpStatus,
        expectedCode: String,
    ) {
        val code = CommonErrorCode.of(handler.categoryOf(status))
        assertEquals(expectedCode, code?.code)
    }
}
