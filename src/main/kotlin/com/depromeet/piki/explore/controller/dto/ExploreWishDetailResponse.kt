package com.depromeet.piki.explore.controller.dto

import com.depromeet.piki.explore.service.dto.ExploreFolderResult
import com.depromeet.piki.explore.service.dto.ExploreOwnerResult
import com.depromeet.piki.explore.service.dto.ExploreWishDetailResult
import com.depromeet.piki.profile.domain.ProfileRelation
import com.depromeet.piki.wishlist.controller.dto.WishItemResponse
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime
import java.util.UUID

@Schema(description = "다른 사람이 담은 위시의 상세")
data class ExploreWishDetailResponse(
    @field:Schema(description = "위시 id", example = "1024")
    val wishId: Long,
    @field:Schema(description = "담긴 시각", example = "2026-11-24T09:58:30+09:00")
    val addedAt: LocalDateTime,
    @field:Schema(description = "보는 사람이 이미 내 위시에 가진 상품인지. 비로그인이면 false", example = "false")
    val saved: Boolean,
    @field:Schema(description = "이 상품을 위시에 담은 사람 수", example = "12")
    val savedUserCount: Int,
    @field:Schema(description = "상품. 내 위시 목록의 item 과 같은 모양이다")
    val item: WishItemResponse.ItemView,
    @field:Schema(
        description =
            "담은 사람. 볼 수 없으면 null 이다(프로필 비공개 · 차단 관계). " +
                "null 이면 화면은 '비공개된 유저 · 비공개된 폴더' 로 그린다",
        nullable = true,
    )
    val owner: ExploreOwnerView?,
) {
    companion object {
        fun from(result: ExploreWishDetailResult): ExploreWishDetailResponse =
            ExploreWishDetailResponse(
                wishId = result.wish.wish.getId(),
                addedAt = result.wish.wish.createdAt,
                saved = result.saved,
                savedUserCount = result.savedUserCount,
                item = WishItemResponse.ItemView.from(result.wish),
                owner = result.owner?.let(ExploreOwnerView::from),
            )
    }
}

@Schema(description = "위시를 담은 사람")
data class ExploreOwnerView(
    @field:Schema(description = "프로필 조회(GET /profiles/{userId})에 쓰는 사용자 id")
    val userId: UUID,
    @field:Schema(description = "닉네임", example = "난폭한 북극곰")
    val nickname: String,
    @field:Schema(description = "프로필 이미지 URL", example = "https://cdn.piki.day/profiles/polar-bear.png")
    val profileImage: String,
    @field:Schema(description = "보는 사람과의 관계. SELF · FRIEND · NOT_FRIEND 중 하나다", example = "FRIEND")
    val relation: ProfileRelation,
    @field:Schema(
        description =
            "이 위시가 든 공개 폴더. 여러 개면 가장 최근에 넣은 폴더다. " +
                "공개 폴더에 들어 있지 않으면 null 이고, 비공개 폴더는 내려가지 않는다",
        nullable = true,
    )
    val folder: ExploreFolderView?,
) {
    companion object {
        fun from(result: ExploreOwnerResult): ExploreOwnerView =
            ExploreOwnerView(
                userId = result.userId,
                nickname = result.nickname,
                profileImage = result.profileImage,
                relation = result.relation,
                folder = result.folder?.let(ExploreFolderView::from),
            )
    }
}

@Schema(description = "위시가 든 공개 폴더")
data class ExploreFolderView(
    @field:Schema(description = "공개 폴더 상세(GET /profiles/{userId}/folders/{folderId})에 쓰는 폴더 id", example = "12")
    val folderId: Long,
    @field:Schema(description = "폴더 이름", example = "11월 위시리스트")
    val name: String,
) {
    companion object {
        fun from(result: ExploreFolderResult): ExploreFolderView = ExploreFolderView(folderId = result.folderId, name = result.name)
    }
}
