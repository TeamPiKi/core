package com.depromeet.piki.explore.service

import com.depromeet.piki.explore.domain.ExploreItem
import com.depromeet.piki.explore.domain.ExplorePickFeed
import com.depromeet.piki.explore.repository.ExplorePickJpaRepository
import com.depromeet.piki.item.domain.ItemSlot
import com.depromeet.piki.item.service.ItemCardService
import com.depromeet.piki.product.source.SourcePlatformResolver
import com.depromeet.piki.tournament.domain.Tournament
import com.depromeet.piki.user.domain.IdentityType
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Component
class ExplorePickFeedBuilder(
    private val explorePickJpaRepository: ExplorePickJpaRepository,
    private val itemCardService: ItemCardService,
    private val sourcePlatformResolver: SourcePlatformResolver,
) {
    // 1위가 드물게 나와 기간으로 자르지 않는다. 최근 3주에 10건 남짓이다(2026-11 운영)
    @Transactional(readOnly = true)
    fun build(): ExplorePickFeed {
        val rows =
            explorePickJpaRepository.findExposedLatest(Tournament.FINAL_ROUND_SIZE, IdentityType.MEMBER, Limit.of(SCAN_SIZE))
        val queries = rows.map { ExploreItem.cardQuery(ItemSlot.tournamentItem(it.candidateId), it.itemId) }
        val cards = itemCardService.cardsOf(queries)
        val picks =
            rows.zip(queries).mapNotNull { (row, query) ->
                val card = cards.getValue(query)
                ExploreItem.machineExtracted(card, sourcePlatformResolver.resolve(card.sourceLink))?.let(row::toPick)
            }
        return ExplorePickFeed.of(picks, ITEM_LIMIT, LocalDateTime.now())
    }

    companion object {
        // 방금 담긴 위시와 같은 이유로 같은 크기다
        private const val ITEM_LIMIT = 100
        private const val SCAN_SIZE = 300
    }
}
