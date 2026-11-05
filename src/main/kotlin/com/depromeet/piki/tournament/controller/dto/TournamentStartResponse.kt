package com.depromeet.piki.tournament.controller.dto

import com.depromeet.piki.item.domain.DiscountRate
import com.depromeet.piki.tournament.service.dto.StartResult

data class TournamentStartResponse(
    val tournamentId: Long,
    val items: List<ItemResponse>,
) {
    data class ItemResponse(
        val tournamentItemId: Long,
        val name: String?,
        val price: Int?,
        val originalPrice: Int?,
        val discountRate: Int?,
        val currency: String?,
        val imageUrl: String?,
    )

    companion object {
        fun from(result: StartResult): TournamentStartResponse =
            TournamentStartResponse(
                tournamentId = result.tournamentId,
                items = result.items.map { item ->
                    ItemResponse(
                        tournamentItemId = item.tournamentItemId,
                        name = item.name,
                        price = item.price,
                        originalPrice = item.originalPrice,
                        discountRate = DiscountRate.of(item.price, item.originalPrice),
                        currency = item.currency,
                        imageUrl = item.imageUrl,
                    )
                },
            )
    }
}
