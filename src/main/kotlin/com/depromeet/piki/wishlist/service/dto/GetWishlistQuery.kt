package com.depromeet.piki.wishlist.service.dto

import com.depromeet.piki.wishlist.domain.WishCursor
import com.depromeet.piki.wishlist.domain.WishlistSize
import java.util.UUID

data class GetWishlistQuery(
    val userId: UUID,
    val cursor: WishCursor?,
    val size: WishlistSize,
)
