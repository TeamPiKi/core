package com.depromeet.piki.wishlist.service.dto

import java.util.UUID

data class ApplyWishUserInputCommand(
    val userId: UUID,
    val wishId: Long,
    val name: String?,
    val price: Int?,
    val imageUrl: String?,
    val currency: String?,
    val memo: String?,
)
