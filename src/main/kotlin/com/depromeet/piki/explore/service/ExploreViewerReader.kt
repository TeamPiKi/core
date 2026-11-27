package com.depromeet.piki.explore.service

import com.depromeet.piki.block.repository.UserBlockRepository
import com.depromeet.piki.wishlist.repository.WishRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

// 공통 피드에 넣을 수 없는, 보는 사람마다 다른 값
@Component
class ExploreViewerReader(
    private val userBlockRepository: UserBlockRepository,
    private val wishRepository: WishRepository,
) {
    @Transactional(readOnly = true)
    fun blockedCounterpartsOf(viewerId: UUID): Set<UUID> = userBlockRepository.findCounterpartIds(viewerId)

    @Transactional(readOnly = true)
    fun savedWishIdByItemId(
        viewerId: UUID,
        itemIds: List<Long>,
    ): Map<Long, Long> {
        if (itemIds.isEmpty()) return emptyMap()
        return wishRepository
            .findByItemIdsAndUserId(itemIds, viewerId)
            .associate { requireNotNull(it.itemId) to it.getId() }
    }
}
