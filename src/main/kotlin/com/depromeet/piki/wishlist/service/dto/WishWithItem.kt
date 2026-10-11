package com.depromeet.piki.wishlist.service.dto

import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.service.ItemCard
import com.depromeet.piki.wishlist.domain.Wish

data class WishWithItem(
    val wish: Wish,
    val item: Item?,
    val card: ItemCard,
    val sourcePlatform: String? = null,
)
