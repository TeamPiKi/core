package com.depromeet.piki.tournament.controller.dto

import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.tournament.service.dto.TournamentItemDetail
import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
data class TournamentItemDetailResponse(
    val tournamentItemId: Long,
    val itemId: Long,
    val sourceUrl: String?,
    val name: String?,
    val imageUrl: String?,
    val price: Int?,
    val currency: String?,
    val status: ItemStatus,
    // 요청자 본인의 위시에 담긴 상품일 때만 그 위시의 개인 메모. NON_NULL 이라 없으면 필드 자체가 빠진다.
    val memo: String? = null,
) {
    companion object {
        fun from(detail: TournamentItemDetail): TournamentItemDetailResponse =
            TournamentItemDetailResponse(
                tournamentItemId = detail.tournamentItemId,
                itemId = detail.itemId,
                sourceUrl = detail.sourceUrl,
                name = detail.name,
                imageUrl = detail.imageUrl,
                price = detail.price,
                currency = detail.currency,
                status = detail.status,
                memo = detail.memo,
            )
    }
}
