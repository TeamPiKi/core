package com.depromeet.piki.tournament.controller.dto

import com.depromeet.piki.product.domain.ProductLink
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class AddTournamentItemFromLinkRequest(
    @field:NotBlank(message = "링크를 입력해 주세요.")
    @field:Size(max = ProductLink.MAX_LENGTH, message = "링크가 너무 길어요.")
    @field:Schema(
        description = "등록할 상품 페이지 URL",
        example = "https://www.example-shop.com/products/12345",
        requiredMode = RequiredMode.REQUIRED,
    )
    val url: String,
)
