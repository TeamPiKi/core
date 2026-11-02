package com.depromeet.piki.wishlist.service.dto

import com.depromeet.piki.item.service.ItemCard
import com.depromeet.piki.wishlist.domain.Wish
import com.depromeet.piki.wishlist.domain.WishFolderResult

data class WishResult(
    val wish: Wish,
    val card: ItemCard,
    val sourcePlatform: String? = null,
    val winnerTournamentIds: List<Long> = emptyList(),
    // 폴더를 지정해 담은 링크가 이미 내 위시일 때만 채운다. 새로 담긴 위시는 null
    val folderResult: WishFolderResult? = null,
)
