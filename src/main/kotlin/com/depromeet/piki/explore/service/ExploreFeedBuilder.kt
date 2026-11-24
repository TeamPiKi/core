package com.depromeet.piki.explore.service

import com.depromeet.piki.explore.domain.ExploreCard
import com.depromeet.piki.explore.domain.ExploreFeed
import com.depromeet.piki.explore.repository.ExploreWishJpaRepository
import com.depromeet.piki.item.service.ItemCardService
import com.depromeet.piki.product.source.SourcePlatformResolver
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Component
class ExploreFeedBuilder(
    private val exploreWishJpaRepository: ExploreWishJpaRepository,
    private val itemCardService: ItemCardService,
    private val sourcePlatformResolver: SourcePlatformResolver,
) {
    @Transactional(readOnly = true)
    fun build(): ExploreFeed {
        val wishes = exploreWishJpaRepository.findExposedLatest(SCAN_SIZE)
        val cards = itemCardService.cardsOf(wishes.map { it.cardQuery() })
        val candidates =
            wishes.mapNotNull { wish ->
                val card = cards.getValue(wish.cardQuery())
                ExploreCard.from(wish, card, sourcePlatformResolver.resolve(card.sourceLink))
            }
        return ExploreFeed.of(candidates, ITEM_LIMIT, LocalDateTime.now())
    }

    companion object {
        private const val ITEM_LIMIT = 300

        // 미완성 카드가 빠지고 같은 상품을 여러 명이 담은 몫만큼 넉넉히 읽는다. 저장되는 카드 수의 상한이기도 하다
        private const val SCAN_SIZE = 1_000
    }
}
