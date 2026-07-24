package com.depromeet.piki.auth.infrastructure.oauth.apple

import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode

// AppleNotificationException 의 code 배정표(에픽 #728). Apple 서버-서버 알림(webhook) 검증 실패를 다룬다.
// 번호는 append-only. status 는 category.httpStatus 로 파생된다.
enum class AppleErrorCode(
    override val code: String,
    override val category: ErrorCategory,
    override val message: String,
) : ErrorCode {
    INVALID_SIGNATURE("APPLE-001", ErrorCategory.UNAUTHORIZED, "유효하지 않은 Apple 서버 알림입니다."),
    PROVIDER_ERROR("APPLE-002", ErrorCategory.RETRYABLE, "Apple 알림 검증 중 외부 키 조회에 실패했습니다."),
}
