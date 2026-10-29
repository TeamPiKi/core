package com.depromeet.piki.wishlist.controller.dto

import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.wishlist.service.dto.RegisterWishFromUrlCommand
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID

@Schema(description = "위시리스트 등록 요청")
data class WishlistRegisterRequest(
    @field:NotBlank(message = "링크를 입력해 주세요.")
    @field:Size(max = ProductLink.MAX_LENGTH, message = "링크가 너무 길어요.")
    @field:Schema(
        description = "등록할 상품 페이지 URL",
        example = "https://www.example-shop.com/products/12345",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val url: String,
) {
    fun toCommand(userId: UUID): RegisterWishFromUrlCommand = RegisterWishFromUrlCommand(userId = userId, link = ProductLink.parse(url))
}
