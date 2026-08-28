package com.depromeet.piki.wishlist.domain

import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode
import com.depromeet.piki.common.exception.ErrorPayload
import com.depromeet.piki.common.exception.HttpMappable
import org.springframework.http.HttpStatus

// 도메인 예외지만 HttpMappable 로 status·category 를 직접 들고 있다. 도메인이 전송 계층(HTTP)을 아는
// 형태는 순수 DDD 에선 피하지만, "사유 + status" 를 예외 정의 한 곳에서 보는 응집도를 위해 의식적으로
// 택한 트레이드오프다. status 매핑을 핸들러로 분리하는 대안은 #181 에서 검토.
// message·category·httpStatus 는 전부 errorCode 하나에서 파생한다(WishErrorCode 가 single source).
class WishException private constructor(
    override val errorCode: ErrorCode,
    override val payload: Map<String, Any>? = null,
) : BaseException(errorCode.message),
    HttpMappable,
    ErrorPayload {
    override val category: ErrorCategory get() = errorCode.category
    override val httpStatus: HttpStatus get() = errorCode.category.httpStatus

    companion object {
        // 중복 응답이 알려주는 기존 위시의 id. 응답 data 의 키라 클라 계약이므로 문자열을 흩뿌리지 않고 여기 한 곳에 둔다.
        const val EXISTING_WISH_ID = "wishId"

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

        // 공유 정체성(#825) 도입으로 비로소 판정 가능해진 앞문 중복 — 같은 사용자가 이미 담은 상품(같은 귀결점)을
        // 또 등록하면 새 카드 대신 409 로 알린다(결정 3c). 별칭 미스로 파싱 후에야 판명되는 뒷문 중복은 여기 안 닿는다.
        //
        // 이미 담긴 위시의 id 를 응답 data 로 함께 내린다(#973) — 사유(409)만으로는 클라가 "이미 담았어요, 보러가기"
        // 를 그릴 수 없어 목록을 다시 조회해야 했다.
        fun alreadyExists(existingWishId: Long): WishException =
            WishException(WishErrorCode.ALREADY_EXISTS, mapOf(EXISTING_WISH_ID to existingWishId))
    }
}
