package com.depromeet.piki.wishlist.controller.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "위시 개수")
data class WishCountResponse(
    @field:Schema(description = "거르기 조건에 맞는 내 위시 수. 지운 위시는 세지 않는다", example = "128")
    val count: Long,
)
