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
        // 요청마다 피드를 통째로 읽으므로 작게 둔다. 하루에 새로 담기는 위시가 10건 안팎이라 100개면 몇 주 치다
        private const val ITEM_LIMIT = 100

        // 미완성 카드가 빠지고 같은 상품을 여러 명이 담은 몫만큼 넉넉히 읽는다. 저장되는 카드 수의 상한이기도 하다
        private const val SCAN_SIZE = 300
    }
}
