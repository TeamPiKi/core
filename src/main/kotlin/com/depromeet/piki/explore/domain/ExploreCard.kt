package com.depromeet.piki.explore.domain

import com.depromeet.piki.item.service.ItemCard
import com.depromeet.piki.wishlist.domain.Wish
import java.time.LocalDateTime
import java.util.UUID

// Redis 에 JSON 으로 저장된다. 필드를 바꾸면 ExploreFeedSerializationCompatibilityTest 의 스냅샷과 맞춘다
data class ExploreCard(
    val wishId: Long,
    val ownerId: UUID,
    val itemId: Long,
    val addedAt: LocalDateTime,
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
        // 이름·가격·이미지가 다 찬 카드만 피드에 오른다
        fun from(
            wish: Wish,
            card: ItemCard,
            sourcePlatform: String?,
        ): ExploreCard? {
            if (!card.isReady()) return null
            val display = card.display ?: return null
            return ExploreCard(
                wishId = wish.getId(),
                ownerId = wish.userId,
                itemId = display.itemId,
                addedAt = wish.createdAt,
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
