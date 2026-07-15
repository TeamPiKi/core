package com.depromeet.piki.common.response

import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode

data class ApiResponseBody<T>(
    val data: T?,
    // 에러 응답의 계약 키(예: "USER-001"). 성공(2xx)은 HTTP status 가 결과를 표현하므로 code=null 이다.
    // (근거: 표준 RFC 9457/9110·Stripe·GitHub 등은 성공에 body code 를 두지 않는다 — 에픽 #728 "근거" 섹션)
    // wire 는 문자열이다. ErrorCode(enum) 는 fail() 경계에서 강타입으로 받아 code 문자열만 여기 싣는다.
    val code: String? = null,
    val detail: String,
    // 모든 응답이 동일한 형태를 갖도록 항상 포함한다. 페이징과 무관한 응답은 "다음 페이지 없음"(EMPTY) 으로 채운다.
    val pageResponse: PageResponse = PageResponse.EMPTY,
) {
    companion object {
        private const val SUCCESS_DETAIL = "완료했어요."

        fun <T> ok(
            data: T? = null,
            pageResponse: PageResponse = PageResponse.EMPTY,
        ): ApiResponseBody<T> = ApiResponseBody(data = data, detail = SUCCESS_DETAIL, pageResponse = pageResponse)

        fun <T> created(data: T? = null): ApiResponseBody<T> = ApiResponseBody(data = data, detail = SUCCESS_DETAIL)

        // code 를 배정한 도메인 예외용 — ErrorCode 를 강타입으로 받아 code·detail 을 채운다.
        // 진입점이 ErrorCode 라 임의 문자열을 code 로 넣는 실수가 컴파일로 차단되고, ErrorCode→wire 문자열 변환은 이 한 곳뿐이다.
        fun <T> fail(
            errorCode: ErrorCode,
            detail: String? = null,
        ): ApiResponseBody<T> =
            ApiResponseBody(data = null, code = errorCode.code, detail = detail ?: errorCode.category.description)

        // code 없는 fallback 경로용(Security 401/403·Bean Validation·generic). 이관 전 예외도 여기로 온다 → code=null.
        fun <T> fail(
            category: ErrorCategory,
            detail: String? = null,
        ): ApiResponseBody<T> = ApiResponseBody(data = null, detail = detail ?: category.description)
    }
}
