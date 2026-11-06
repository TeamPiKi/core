package com.depromeet.piki.tournament.service.dto

data class RankedItem(
    val rank: Int,
    val tournamentItemId: Long,
    val itemId: Long,
    val name: String?,
    val price: Int?,
    val originalPrice: Int?,
    val discountRate: Int?,
    val currency: String?,
    val imageUrl: String?,
)
