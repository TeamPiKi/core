package com.depromeet.piki.friendship.domain

import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode
import com.depromeet.piki.common.exception.HttpMappable
import org.springframework.http.HttpStatus

// message·category·httpStatus 는 전부 errorCode 하나에서 파생한다(FriendshipErrorCode 가 single source).
class FriendshipException private constructor(
    override val errorCode: ErrorCode,
) : BaseException(errorCode.message),
    HttpMappable {
    override val category: ErrorCategory get() = errorCode.category
    override val httpStatus: HttpStatus get() = errorCode.category.httpStatus

    companion object {
        fun guestCannotUse(): FriendshipException = FriendshipException(FriendshipErrorCode.GUEST_CANNOT_USE)

        fun selfNotAllowed(): FriendshipException = FriendshipException(FriendshipErrorCode.SELF_NOT_ALLOWED)
    }
}
