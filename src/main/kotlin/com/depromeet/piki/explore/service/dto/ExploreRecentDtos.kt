package com.depromeet.piki.explore.service.dto

import com.depromeet.piki.explore.domain.ExploreCard
import com.depromeet.piki.wishlist.domain.WishCursor
import com.depromeet.piki.wishlist.domain.WishlistSize
import java.time.LocalDateTime
import java.util.UUID

data class GetExploreRecentQuery(
    val viewerId: UUID?,
    val cursor: WishCursor?,
    val size: WishlistSize,
)

data class ExploreRecentResult(
    // 피드가 아직 한 번도 만들어지지 않았으면 null
    val updatedAt: LocalDateTime?,
    val entries: List<ExploreWishResult>,
    val nextCursor: String?,
    val hasNext: Boolean,
) {
    companion object {
        val EMPTY = ExploreRecentResult(updatedAt = null, entries = emptyList(), nextCursor = null, hasNext = false)
    }
}

data class ExploreWishResult(
    val card: ExploreCard,
    val savedWishId: Long?,
) {
    val saved: Boolean get() = savedWishId?.let { true } ?: false
}
