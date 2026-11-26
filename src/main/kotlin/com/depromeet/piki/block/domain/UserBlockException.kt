package com.depromeet.piki.block.domain

import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode
import com.depromeet.piki.common.exception.HttpMappable
import org.springframework.http.HttpStatus

// message·category·httpStatus 는 전부 errorCode 하나에서 파생한다(UserBlockErrorCode 가 single source).
class UserBlockException private constructor(
    override val errorCode: ErrorCode,
) : BaseException(errorCode.message),
    HttpMappable {
    override val category: ErrorCategory get() = errorCode.category
    override val httpStatus: HttpStatus get() = errorCode.category.httpStatus

    companion object {
        fun guestCannotUse(): UserBlockException = UserBlockException(UserBlockErrorCode.GUEST_CANNOT_USE)

        fun selfNotAllowed(): UserBlockException = UserBlockException(UserBlockErrorCode.SELF_NOT_ALLOWED)
    }
}
