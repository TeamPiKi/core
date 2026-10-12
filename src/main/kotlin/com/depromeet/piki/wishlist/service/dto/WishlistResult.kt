package com.depromeet.piki.wishlist.service.dto

data class WishlistResult(
    val entries: List<WishResult>,
    val nextCursor: String?,
    val hasNext: Boolean,
)
