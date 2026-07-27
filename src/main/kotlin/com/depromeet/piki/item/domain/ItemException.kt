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
        // 등록 완료(READY)된 item 은 링크에서 기계 추출한 사실이라 클라이언트가 직접 수정할 수 없다.
        // 갱신은 서버 재추출 경로로만 들어오고, 클라이언트 보정은 추출 실패(FAILED) 항목에 한정된다.
        fun alreadyReady(): ItemException = ItemException(ItemErrorCode.ALREADY_READY)

        // 파싱 중(PROCESSING)인 item 은 백그라운드 워커가 결과를 채우는 중이라 클라이언트가 끼어들 수 없다.
        fun stillProcessing(): ItemException = ItemException(ItemErrorCode.STILL_PROCESSING)

        // FAILED 항목 복구의 입력 경계 계약 — 필수 필드 없이 보정하면 "쓸 수 있는 상품"(READY)이 될 수 없다.
        // 엔티티 불변식(requireReadyInvariant)이 최후의 보루라면, 이건 그 전에 클라이언트에게 400 으로 돌려주는 경계다.
        fun nameRequiredForReady(): ItemException = ItemException(ItemErrorCode.NAME_REQUIRED_FOR_READY)

        fun priceRequiredForReady(): ItemException = ItemException(ItemErrorCode.PRICE_REQUIRED_FOR_READY)

        fun imageRequiredForReady(): ItemException = ItemException(ItemErrorCode.IMAGE_REQUIRED_FOR_READY)
    }
}
