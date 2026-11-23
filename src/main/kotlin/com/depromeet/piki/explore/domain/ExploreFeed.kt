package com.depromeet.piki.explore.domain

import java.time.LocalDateTime
import java.util.UUID

data class ExploreFeed(
    val builtAt: LocalDateTime,
    val cards: List<ExploreCard>,
) {
    // 커서가 목록의 위치가 아니라 위시 id 라, 스크롤 도중 피드가 다시 만들어져도 이어진다
    fun page(
        afterWishId: Long?,
        size: Int,
        hiddenOwnerIds: Set<UUID> = emptySet(),
    ): List<ExploreCard> =
        cards
            .asSequence()
            .filter { it.ownerId !in hiddenOwnerIds }
            .filter { card -> afterWishId?.let { card.wishId < it } ?: true }
            .take(size)
            .toList()

    companion object {
        // 같은 상품은 가장 최근에 담긴 한 장만 남긴다
        fun of(
            candidates: Collection<ExploreCard>,
            limit: Int,
            builtAt: LocalDateTime,
        ): ExploreFeed =
            ExploreFeed(
                builtAt = builtAt,
                cards =
                    candidates
                        .sortedByDescending { it.wishId }
                        .distinctBy { it.itemId }
                        .take(limit),
            )
    }
}
