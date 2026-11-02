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
    @field:Schema(
        description =
            "담으면서 함께 넣을 내 폴더 id (폴더 상세에서 담을 때). 없으면 전체 위시에만 담는다. " +
                "이미 담은 링크면 409 대신 그 위시를 이 폴더에 넣고 folderResult 로 결과를 알린다",
        example = "12",
        nullable = true,
    )
    val folderId: Long? = null,
) {
    fun toCommand(userId: UUID): RegisterWishFromUrlCommand = RegisterWishFromUrlCommand(userId = userId, link = ProductLink.parse(url), folderId = folderId)
}
