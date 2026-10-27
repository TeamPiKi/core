package com.depromeet.piki.wishlist.service.dto

import com.depromeet.piki.item.domain.ItemSnapshot

data class WishDetailResult(
    val wishResult: WishResult,
    val memo: String?,
    val history: List<ItemSnapshot>,
)
