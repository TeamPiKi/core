package com.depromeet.piki.common.exception

import org.springframework.http.HttpStatus

// httpStatus 는 category 가 소유한다(category → HttpStatus 1:1). 예외/ErrorCode 는 status 를 직접 들지 않고
// category.httpStatus 로 파생하므로, "code 는 400 인데 category 는 CONFLICT" 같은 엇갈림이 원천 차단된다(#181 해소).
// description 은 fail(category) 의 fallback detail 로 클라이언트 응답에 그대로 나가는 사용자 대면 문구다
// (개발자용 분류 설명이 아니다). 401·403·검증 fallback·500 등 code 없는 경로에서 detail 미지정 시 이 문구가 응답된다.
// 분류의 의미 구분은 enum 이름이 담당하므로, 여기엔 사용자에게 보일 친화 문구만 둔다.
enum class ErrorCategory(
    val httpStatus: HttpStatus,
    val description: String,
) {
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "다시 한번 확인해 주세요."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요해요."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근할 수 없는 페이지예요."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "요청하신 정보를 찾을 수 없어요."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식이에요."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 형식이에요."),
    CONFLICT(HttpStatus.CONFLICT, "요청을 처리하지 못했어요. 다시 확인해 주세요."),

    // RETRYABLE·SERVER_BUSY·SERVER_ERROR 는 셋 다 5xx 지만 클라의 재시도 방식이 다르다. description 을 구분해
    // 재시도 여부 오해를 없앤다: RETRYABLE=즉시 재시도, SERVER_BUSY=대기 후 재시도, SERVER_ERROR=재시도 무의미.
    RETRYABLE(HttpStatus.BAD_GATEWAY, "일시적인 오류가 발생했어요. 잠시 후 다시 시도해 주세요."),
    SERVER_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "지금 요청이 많아요. 잠시 후 다시 시도해 주세요."),
    SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버에 문제가 발생했어요. 불편을 드려 죄송해요."),
}
