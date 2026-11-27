package com.depromeet.piki.explore.controller.dto

import com.depromeet.piki.explore.service.dto.ExploreFriendResult
import com.depromeet.piki.explore.service.dto.ExploreFriendWishResult
import com.depromeet.piki.explore.service.dto.ExploreFriendsResult
import com.depromeet.piki.explore.service.dto.ExploreWishCopyResult
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime
import java.util.UUID

@Schema(description = "탐색의 친구가 담은 위시")
data class ExploreFriendsResponse(
    @field:Schema(description = "상단 친구 칩. 최근에 위시를 담은 친구 순이고, 위시가 없는 친구도 들어 있다")
    val friends: List<ExploreFriendView>,
    @field:Schema(description = "최근에 담긴 순")
    val wishes: List<ExploreFriendWishResponse>,
) {
    companion object {
        fun from(result: ExploreFriendsResult): ExploreFriendsResponse =
            ExploreFriendsResponse(
                friends = result.friends.map(ExploreFriendView::from),
                wishes = result.entries.map(ExploreFriendWishResponse::from),
            )
    }
}

@Schema(description = "친구")
data class ExploreFriendView(
    val userId: UUID,
    @field:Schema(description = "닉네임", example = "잠자는 고양이")
    val nickname: String,
    @field:Schema(description = "프로필 이미지 URL", example = "https://cdn.piki.day/profiles/cat.png")
    val profileImage: String,
) {
    companion object {
        fun from(result: ExploreFriendResult): ExploreFriendView =
            ExploreFriendView(userId = result.userId, nickname = result.nickname, profileImage = result.profileImage)
    }
}

@Schema(description = "친구가 담은 위시 카드 한 장")
data class ExploreFriendWishResponse(
    @field:Schema(description = "위시 id. 위시 상세와 내 위시로 담기에 쓴다", example = "1024")
    val wishId: Long,
    @field:Schema(description = "담긴 시각", example = "2026-11-24T09:58:30+09:00")
    val addedAt: LocalDateTime,
    @field:Schema(description = "내가 이미 내 위시에 가진 상품인지", example = "false")
    val saved: Boolean,
    @field:Schema(
        description = "보는 사람이 이 상품을 담은 내 위시의 id. 채워진 하트를 다시 누르면 이 id 로 위시 삭제(DELETE /api/v1/wishlists/{wishId})를 부른다. saved 가 false 면 null",
        example = "1900",
        nullable = true,
    )
    val savedWishId: Long?,
    val item: ExploreItemView,
    @field:Schema(description = "이 위시를 담은 친구")
    val friend: ExploreFriendView,
) {
    companion object {
        fun from(result: ExploreFriendWishResult): ExploreFriendWishResponse =
            ExploreFriendWishResponse(
                wishId = result.card.wishId,
                addedAt = result.card.addedAt,
                saved = result.saved,
                savedWishId = result.savedWishId,
                item = ExploreItemView.from(result.card),
                friend = ExploreFriendView.from(result.friend),
            )
    }
}

@Schema(description = "내 위시로 담은 결과")
data class ExploreWishCopyResponse(
    @field:Schema(description = "내 위시의 id. 이미 가진 상품이면 원래 있던 위시의 id 다", example = "2048")
    val wishId: Long,
    @field:Schema(description = "이미 내 위시에 있던 상품인지. true 면 새로 만들지 않았다", example = "false")
    val alreadySaved: Boolean,
) {
    companion object {
        fun from(result: ExploreWishCopyResult): ExploreWishCopyResponse =
            ExploreWishCopyResponse(wishId = result.wishId, alreadySaved = result.alreadySaved)
    }
}
