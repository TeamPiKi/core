package com.depromeet.piki.explore.domain

import java.time.LocalDateTime
import java.util.UUID

// cards 는 최근에 담긴 순이고, 같은 상품을 담은 여러 사람의 카드가 함께 들어 있다
data class ExploreFeed(
    val builtAt: LocalDateTime,
    val cards: List<ExploreCard>,
) {
    // 상품당 한 장은 가릴 사람을 뺀 뒤에 고른다. 가장 최근에 담은 사람이 가려져도 같은 상품을 담은 다음 사람의 카드가 나온다.
    // 커서는 그 뒤에 건다. 먼저 걸면 앞 쪽에 나온 상품이 더 일찍 담긴 카드로 다음 쪽에 또 나온다.
    // 커서가 목록의 위치가 아니라 위시 id 라, 스크롤 도중 피드가 다시 만들어져도 이어진다
    fun page(
        afterWishId: Long?,
        size: Int,
        hiddenOwnerIds: Set<UUID> = emptySet(),
    ): List<ExploreCard> =
        cards
            .asSequence()
            .filter { it.ownerId !in hiddenOwnerIds }
            .distinctBy { it.itemId }
            .filter { card -> afterWishId?.let { card.wishId < it } ?: true }
            .take(size)
            .toList()

    companion object {
        // 최근에 담긴 상품 itemLimit 개까지, 그 상품을 담은 카드를 전부 남긴다
        fun of(
            candidates: Collection<ExploreCard>,
            itemLimit: Int,
            builtAt: LocalDateTime,
        ): ExploreFeed {
            val latestFirst = candidates.sortedByDescending { it.wishId }
            val itemIds =
                latestFirst
                    .asSequence()
                    .map { it.itemId }
                    .distinct()
                    .take(itemLimit)
                    .toSet()
            return ExploreFeed(builtAt = builtAt, cards = latestFirst.filter { it.itemId in itemIds })
        }
    }
}
