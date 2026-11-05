package com.depromeet.piki.tournament.controller.dto

import com.depromeet.piki.item.domain.DiscountRate
import com.depromeet.piki.tournament.service.dto.RankedItem
import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
data class RankedItemResponse(
    val rank: Int,
    val tournamentItemId: Long,
    val itemId: Long,
    val name: String?,
    val price: Int?,
    val originalPrice: Int?,
    val discountRate: Int?,
    val currency: String?,
    val imageUrl: String?,
) {
    companion object {
        fun from(r: RankedItem): RankedItemResponse =
            RankedItemResponse(
                rank = r.rank,
                tournamentItemId = r.tournamentItemId,
                itemId = r.itemId,
                name = r.name,
                price = r.price,
                originalPrice = r.originalPrice,
                discountRate = DiscountRate.of(r.price, r.originalPrice),
                currency = r.currency,
                imageUrl = r.imageUrl,
            )
    }
}
