package com.depromeet.piki.wishlist.service.dto

import java.util.UUID

data class UpdateWishMemoCommand(
    val userId: UUID,
    val wishId: Long,
    val memo: String,
)
