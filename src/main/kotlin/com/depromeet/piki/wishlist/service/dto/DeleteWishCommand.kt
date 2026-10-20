package com.depromeet.piki.wishlist.service.dto

import java.util.UUID

data class DeleteWishCommand(
    val userId: UUID,
    val wishId: Long,
)
