package com.depromeet.piki.wishlist.service.dto

import com.depromeet.piki.wishlist.domain.WishDeleteIds
import java.util.UUID

data class DeleteWishesCommand(
    val userId: UUID,
    val wishDeleteIds: WishDeleteIds,
)
