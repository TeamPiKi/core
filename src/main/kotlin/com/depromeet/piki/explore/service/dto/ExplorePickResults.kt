package com.depromeet.piki.explore.service.dto

import com.depromeet.piki.explore.domain.ExploreItem
import com.depromeet.piki.explore.domain.ExplorePick
import com.depromeet.piki.wishlist.domain.WishCursor
import com.depromeet.piki.wishlist.domain.WishlistSize
import java.time.LocalDateTime
import java.util.UUID

data class GetExplorePicksQuery(
    val viewerId: UUID?,
    val cursor: WishCursor?,
    val size: WishlistSize,
)

data class ExplorePicksResult(
    // 피드가 아직 한 번도 만들어지지 않았으면 null
    val updatedAt: LocalDateTime?,
    val entries: List<ExplorePickEntryResult>,
    val nextCursor: String?,
    val hasNext: Boolean,
) {
    companion object {
        val EMPTY = ExplorePicksResult(updatedAt = null, entries = emptyList(), nextCursor = null, hasNext = false)
    }
}

data class ExplorePickEntryResult(
    val pick: ExplorePick,
    val savedWishId: Long?,
) {
    val saved: Boolean get() = savedWishId?.let { true } ?: false
}

data class GetExplorePickQuery(
    val viewerId: UUID?,
    val pickId: Long,
)

data class ExplorePickDetailResult(
    val pick: ExplorePick,
    val picker: ExploreOwnerResult,
    val candidates: List<ExploreCandidateResult>,
    val savedWishId: Long?,
    val savedUserCount: Int,
) {
    val saved: Boolean get() = savedWishId?.let { true } ?: false
}

data class ExplorePickCandidatesResult(
    val pick: ExplorePick,
    val candidates: List<ExploreCandidateResult>,
)

data class ExploreCandidateResult(
    val candidateId: Long,
    val item: ExploreItem,
    val isPick: Boolean,
    val savedWishId: Long?,
) {
    val saved: Boolean get() = savedWishId?.let { true } ?: false
}

data class CopyExploreCandidateCommand(
    val userId: UUID,
    val pickId: Long,
    val candidateId: Long,
)
