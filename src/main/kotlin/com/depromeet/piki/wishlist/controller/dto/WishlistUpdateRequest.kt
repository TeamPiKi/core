package com.depromeet.piki.wishlist.controller.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size

@Schema(description = "위시 항목 수정 요청 — 들어온 필드만 갱신한다")
data class WishlistUpdateRequest(
    @field:Schema(description = "수정할 상품명", example = "에어 조던 1 미드", nullable = true)
    @field:Size(max = 512, message = NAME_MAX_MESSAGE)
    val name: String? = null,
    @field:Schema(description = "수정할 현재 판매가", example = "119000", nullable = true)
    @field:Min(value = 0, message = PRICE_MIN_MESSAGE)
    val price: Int? = null,
    @field:Schema(description = "수정할 통화 코드 (ISO 4217)", example = "KRW", nullable = true)
    @field:Size(max = 8, message = CURRENCY_MAX_MESSAGE)
    val currency: String? = null,
) {
    // Bean Validation 위반 메시지의 single source. OpenAPI example(WishlistApiExamples)이 같은 상수를 참조해
    // "필드 검증 문구가 @field 와 example 두 곳에서 따로 노는" 어긋남을 컴파일 타임에 막는다.
    companion object {
        const val NAME_MAX_MESSAGE = "상품명은 512자까지 입력할 수 있어요."
        const val PRICE_MIN_MESSAGE = "가격은 0원 이상으로 입력해 주세요."
        const val CURRENCY_MAX_MESSAGE = "통화 코드는 8자까지 입력할 수 있어요."
    }
}
