package com.depromeet.piki.item.domain

import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode
import com.depromeet.piki.common.exception.HttpMappable
import org.springframework.http.HttpStatus

// 도메인 예외지만 HttpMappable 로 status·category 를 직접 들고 있다. 도메인이 전송 계층(HTTP)을 아는
// 형태는 순수 DDD 에선 피하지만, "사유 + status" 를 예외 정의 한 곳에서 보는 응집도를 위해 의식적으로
// 택한 트레이드오프다 (WishException 과 동일). status 매핑을 핸들러로 분리하는 대안은 #181 에서 검토.
// message·category·httpStatus 는 전부 errorCode 하나에서 파생한다(ItemErrorCode 가 single source).
class ItemException private constructor(
    override val errorCode: ErrorCode,
) : BaseException(errorCode.message),
    HttpMappable {
    override val category: ErrorCategory get() = errorCode.category
    override val httpStatus: HttpStatus get() = errorCode.category.httpStatus

    companion object {
        fun nameRequiredForReady(): ItemException = ItemException(ItemErrorCode.NAME_REQUIRED_FOR_READY)

        fun priceRequiredForReady(): ItemException = ItemException(ItemErrorCode.PRICE_REQUIRED_FOR_READY)

        fun imageRequiredForReady(): ItemException = ItemException(ItemErrorCode.IMAGE_REQUIRED_FOR_READY)

        fun notRefreshable(): ItemException = ItemException(ItemErrorCode.NOT_REFRESHABLE)

        fun parsingInProgress(): ItemException = ItemException(ItemErrorCode.PARSING_IN_PROGRESS)
    }
}
