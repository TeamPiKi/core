package com.depromeet.piki.explore.domain

import java.time.LocalDateTime
import java.util.UUID

// picks 는 최근에 고른 순이고, 같은 상품을 1위로 고른 여러 사람의 기록이 함께 들어 있다.
// 상품당 한 장을 고르는 순서와 커서를 거는 순서는 ExploreFeed 와 같은 이유로 같다
data class ExplorePickFeed(
    val builtAt: LocalDateTime,
    val picks: List<ExplorePick>,
) {
    fun page(
        afterPickId: Long?,
        size: Int,
        hiddenPickerIds: Set<UUID> = emptySet(),
    ): List<ExplorePick> =
        picks
            .asSequence()
            .filter { it.pickerId !in hiddenPickerIds }
            .distinctBy { it.item.itemId }
            .filter { pick -> afterPickId?.let { pick.pickId < it } ?: true }
            .take(size)
            .toList()

    companion object {
        fun of(
            candidates: Collection<ExplorePick>,
            itemLimit: Int,
            builtAt: LocalDateTime,
        ): ExplorePickFeed {
            val latestFirst = candidates.sortedByDescending { it.pickId }
            val itemIds =
                latestFirst
                    .asSequence()
                    .map { it.item.itemId }
                    .distinct()
                    .take(itemLimit)
                    .toSet()
            return ExplorePickFeed(builtAt = builtAt, picks = latestFirst.filter { it.item.itemId in itemIds })
        }
    }
}
