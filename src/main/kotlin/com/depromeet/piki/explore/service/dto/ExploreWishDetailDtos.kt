package com.depromeet.piki.explore.service.dto

import com.depromeet.piki.profile.domain.ProfileRelation
import com.depromeet.piki.wishlist.service.dto.WishResult
import java.util.UUID

data class GetExploreWishQuery(
    val viewerId: UUID?,
    val wishId: Long,
)

data class ExploreWishDetailResult(
    val wish: WishResult,
    // 담은 사람을 볼 수 없으면 null (프로필 비공개 · 차단 관계)
    val owner: ExploreOwnerResult?,
    val savedWishId: Long?,
    val savedUserCount: Int,
) {
    val saved: Boolean get() = savedWishId?.let { true } ?: false
}

data class ExploreOwnerResult(
    val userId: UUID,
    val nickname: String,
    val profileImage: String,
    val relation: ProfileRelation,
    // 공개 폴더에 들어 있지 않으면 null
    val folder: ExploreFolderResult?,
)

data class ExploreFolderResult(
    val folderId: Long,
    val name: String,
)
