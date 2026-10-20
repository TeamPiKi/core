package com.depromeet.piki.wishlist.service.dto

import com.depromeet.piki.item.service.ItemCard
import com.depromeet.piki.wishlist.domain.Wish

data class WishResult(
    val wish: Wish,
    val card: ItemCard,
    val sourcePlatform: String? = null,
    val winnerTournamentIds: List<Long> = emptyList(),
)
