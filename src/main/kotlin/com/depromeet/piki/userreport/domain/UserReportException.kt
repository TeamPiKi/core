package com.depromeet.piki.userreport.domain

import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode
import com.depromeet.piki.common.exception.HttpMappable
import org.springframework.http.HttpStatus

// message·category·httpStatus 는 전부 errorCode 하나에서 파생한다(UserReportErrorCode 가 single source).
class UserReportException private constructor(
    override val errorCode: ErrorCode,
) : BaseException(errorCode.message),
    HttpMappable {
    override val category: ErrorCategory get() = errorCode.category
    override val httpStatus: HttpStatus get() = errorCode.category.httpStatus

    companion object {
        fun guestCannotUse(): UserReportException = UserReportException(UserReportErrorCode.GUEST_CANNOT_USE)

        fun selfNotAllowed(): UserReportException = UserReportException(UserReportErrorCode.SELF_NOT_ALLOWED)

        fun detailNotAllowed(): UserReportException = UserReportException(UserReportErrorCode.DETAIL_NOT_ALLOWED)
    }
}
