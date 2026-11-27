package com.depromeet.piki.explore.service.dto

import com.depromeet.piki.explore.domain.ExploreCard
import com.depromeet.piki.wishlist.domain.WishCursor
import com.depromeet.piki.wishlist.domain.WishlistSize
import java.util.UUID

data class GetExploreFriendsQuery(
    val viewerId: UUID,
    val friendId: UUID?,
    val cursor: WishCursor?,
    val size: WishlistSize,
)

data class ExploreFriendsResult(
    // 최근에 위시를 담은 친구 순. 위시가 없는 친구는 뒤에 온다
    val friends: List<ExploreFriendResult>,
    val entries: List<ExploreFriendWishResult>,
    val nextCursor: String?,
    val hasNext: Boolean,
)

data class ExploreFriendResult(
    val userId: UUID,
    val nickname: String,
    val profileImage: String,
)

data class ExploreFriendWishResult(
    val card: ExploreCard,
    val friend: ExploreFriendResult,
    val savedWishId: Long?,
) {
    val saved: Boolean get() = savedWishId?.let { true } ?: false
}
