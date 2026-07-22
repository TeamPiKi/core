package com.depromeet.piki.notification.domain

import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode
import com.depromeet.piki.common.exception.HttpMappable
import org.springframework.http.HttpStatus

// 알림 도메인 커스텀 예외. message·category·httpStatus 는 전부 errorCode 하나에서 파생한다(NotificationErrorCode 가 single source).
class NotificationException private constructor(
    override val errorCode: ErrorCode,
) : BaseException(errorCode.message),
    HttpMappable {
    override val category: ErrorCategory get() = errorCode.category
    override val httpStatus: HttpStatus get() = errorCode.category.httpStatus

    companion object {
        fun invalidCursor(): NotificationException = NotificationException(NotificationErrorCode.INVALID_CURSOR)
    }
}
