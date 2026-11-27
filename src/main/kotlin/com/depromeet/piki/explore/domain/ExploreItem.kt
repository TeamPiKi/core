package com.depromeet.piki.explore.domain

import com.depromeet.piki.item.domain.CardQuery
import com.depromeet.piki.item.domain.ItemSlot
import com.depromeet.piki.item.service.ItemCard
import java.util.UUID

// Redis 에 JSON 으로 저장된다. 필드를 바꾸면 ExplorePickFeedSerializationCompatibilityTest 의 스냅샷과 맞춘다
data class ExploreItem(
    val itemId: Long,
    val name: String,
    val price: Int,
    val originalPrice: Int?,
    val discountRate: Int?,
    val currency: String?,
    val imageUrl: String,
    val sourceUrl: String?,
    val sourcePlatform: String?,
) {
    companion object {
        // 누구의 입력도 자기 것으로 잡히지 않는 사람
        val NOBODY: UUID = UUID(0, 0)

        // 토너먼트 화면과 달리 시작 시각의 값이 아니라 지금 값을 쓴다. 탐색에서 담는 상품은 지금 파는 상품이다
        fun cardQuery(
            slot: ItemSlot,
            itemId: Long,
        ): CardQuery = CardQuery(slot = slot, itemId = itemId, owner = NOBODY)

        // 후보를 올린 사람은 탐색에 드러나지 않아 그 사람이 직접 고친 값도 싣지 않는다. card 는 cardQuery 로 계산한 것이다
        fun machineExtracted(
            card: ItemCard,
            sourcePlatform: String?,
        ): ExploreItem? {
            if (!card.isReady()) return null
            val display = card.display ?: return null
            if (display.userInput) return null
            return ExploreItem(
                itemId = display.itemId,
                name = requireNotNull(display.name),
                price = requireNotNull(display.price),
                originalPrice = display.originalPrice,
                discountRate = display.discountRate,
                currency = display.currency,
                imageUrl = requireNotNull(display.imageUrl),
                sourceUrl = card.sourceLink?.toString(),
                sourcePlatform = sourcePlatform,
            )
        }
    }
}
