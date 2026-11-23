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
        return ExploreFeed.of(candidates, FEED_SIZE, LocalDateTime.now())
    }

    companion object {
        private const val FEED_SIZE = 300

        // 미완성 카드와 같은 상품의 중복이 빠질 몫만큼 넉넉히 읽는다
        private const val SCAN_SIZE = 1_000
    }
}
