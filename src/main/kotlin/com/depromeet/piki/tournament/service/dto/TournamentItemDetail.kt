package com.depromeet.piki.tournament.service.dto

import com.depromeet.piki.item.domain.ItemStatus

data class TournamentItemDetail(
    val tournamentItemId: Long,
    val itemId: Long,
    val sourceUrl: String?,
    val name: String?,
    val imageUrl: String?,
    val price: Int?,
    val currency: String?,
    val status: ItemStatus,
    // 요청자 본인의 위시에 담긴 상품일 때만 그 위시의 개인 메모(#906). 아니면 null.
    val memo: String?,
)
