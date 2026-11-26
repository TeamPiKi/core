package com.depromeet.piki.explore.service

import com.depromeet.piki.explore.repository.ExploreFeedStore
import com.depromeet.piki.explore.service.dto.ExploreRecentResult
import com.depromeet.piki.explore.service.dto.ExploreWishResult
import com.depromeet.piki.explore.service.dto.GetExploreRecentQuery
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ExploreService(
    private val exploreFeedStore: ExploreFeedStore,
    private val exploreViewerReader: ExploreViewerReader,
) {
    // 트랜잭션을 걸지 않는다. Redis 를 읽는 동안 DB 커넥션을 쥐지 않고, 비로그인 요청은 DB 를 건드리지 않는다.
    // 피드가 없어도 여기서 만들지 않는다. 요청마다 재생성 조회가 도는 것을 막고 다음 주기에 맡긴다
    fun getRecent(query: GetExploreRecentQuery): ExploreRecentResult {
        val feed = exploreFeedStore.find() ?: return ExploreRecentResult.EMPTY
        val size = query.size.value
        val cards = feed.page(query.cursor?.position, size + 1, blockedCounterpartsOf(query.viewerId))
        val page = cards.take(size)
        val hasNext = cards.size > size
        val savedItemIds = savedItemIdsOf(query.viewerId, page.map { it.itemId })
        return ExploreRecentResult(
            updatedAt = feed.builtAt,
            entries = page.map { ExploreWishResult(card = it, saved = it.itemId in savedItemIds) },
            nextCursor = page.lastOrNull()?.takeIf { hasNext }?.wishId?.toString(),
            hasNext = hasNext,
        )
    }

    private fun blockedCounterpartsOf(viewerId: UUID?): Set<UUID> {
        viewerId ?: return emptySet()
        return exploreViewerReader.blockedCounterpartsOf(viewerId)
    }

    private fun savedItemIdsOf(
        viewerId: UUID?,
        itemIds: List<Long>,
    ): Set<Long> {
        viewerId ?: return emptySet()
        return exploreViewerReader.savedItemIdsOf(viewerId, itemIds)
    }
}
