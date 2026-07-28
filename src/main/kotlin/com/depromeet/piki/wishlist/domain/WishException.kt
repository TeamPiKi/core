package com.depromeet.piki.wishlist.domain

import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode
import com.depromeet.piki.common.exception.HttpMappable
import org.springframework.http.HttpStatus

// 도메인 예외지만 HttpMappable 로 status·category 를 직접 들고 있다. 도메인이 전송 계층(HTTP)을 아는
// 형태는 순수 DDD 에선 피하지만, "사유 + status" 를 예외 정의 한 곳에서 보는 응집도를 위해 의식적으로
// 택한 트레이드오프다. status 매핑을 핸들러로 분리하는 대안은 #181 에서 검토.
// message·category·httpStatus 는 전부 errorCode 하나에서 파생한다(WishErrorCode 가 single source).
class WishException private constructor(
    override val errorCode: ErrorCode,
) : BaseException(errorCode.message),
    HttpMappable {
    override val category: ErrorCategory get() = errorCode.category
    override val httpStatus: HttpStatus get() = errorCode.category.httpStatus

    companion object {
        // 위시리스트는 회원 전용 — 게스트(인증은 됐으나 회원 아님)가 정상 요청으로 닿을 수 있는 계약 응답이라 커스텀 예외(403).
        // Security 에서 MEMBER 만 허용하면 detail 없는 권한 없음 403 으로 떨어져 "회원 전용" 사유를 못 전달하므로,
        // authenticated() 로 통과시킨 뒤 서비스가 이 예외로 막는다(UserException.guestCannotWithdraw 와 같은 패턴).
        fun guestCannotUseWishlist(): WishException = WishException(WishErrorCode.GUEST_CANNOT_USE_WISHLIST)

        fun forbiddenWishItems(): WishException = WishException(WishErrorCode.FORBIDDEN_WISH_ITEMS)

        fun invalidCursor(): WishException = WishException(WishErrorCode.INVALID_CURSOR)

        fun notFound(): WishException = WishException(WishErrorCode.NOT_FOUND)

        fun invalidImageCount(): WishException = WishException(WishErrorCode.INVALID_IMAGE_COUNT)

        fun invalidIdCount(): WishException = WishException(WishErrorCode.INVALID_ID_COUNT)

        fun notRefreshable(): WishException = WishException(WishErrorCode.NOT_REFRESHABLE)

        fun failedNotRefreshable(): WishException = WishException(WishErrorCode.FAILED_NOT_REFRESHABLE)
    }
}
