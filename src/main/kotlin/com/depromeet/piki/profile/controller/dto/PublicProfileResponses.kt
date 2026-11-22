package com.depromeet.piki.profile.controller.dto

import com.depromeet.piki.profile.domain.ProfileRelation
import com.depromeet.piki.profile.service.dto.PublicFolderDetail
import com.depromeet.piki.profile.service.dto.PublicFolderSummary
import com.depromeet.piki.profile.service.dto.PublicProfile
import com.depromeet.piki.wishlist.controller.dto.WishItemResponse
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

@Schema(description = "다른 사람 프로필 (내 프로필도 같은 모양)")
data class PublicProfileResponse(
    val userId: UUID,
    @field:Schema(description = "닉네임. 항상 공개된다", example = "난폭한 북극곰")
    val nickname: String,
    @field:Schema(description = "프로필 이미지 URL. 항상 공개된다")
    val profileImage: String,
    @field:Schema(description = "보는 사람과의 관계. 화면이 버튼과 상단 아이콘을 이걸로 가른다")
    val relation: ProfileRelation,
    @field:Schema(description = "공개 폴더. 볼 수 없으면 빈 배열 — 공개한 폴더가 없는 경우와 같은 모양이다")
    val folders: List<PublicFolderResponse>,
) {
    companion object {
        fun from(profile: PublicProfile): PublicProfileResponse =
            PublicProfileResponse(
                userId = profile.userId,
                nickname = profile.nickname,
                profileImage = profile.profileImage,
                relation = profile.relation,
                folders = profile.folders.map { PublicFolderResponse.from(it) },
            )
    }
}

@Schema(description = "프로필에 뜨는 공개 폴더 카드")
data class PublicFolderResponse(
    val folderId: Long,
    val name: String,
    @field:Schema(description = "그 폴더에 든 위시 수", example = "12")
    val wishCount: Int,
) {
    companion object {
        fun from(folder: PublicFolderSummary): PublicFolderResponse =
            PublicFolderResponse(folderId = folder.folderId, name = folder.name, wishCount = folder.wishCount)
    }
}

@Schema(description = "공개 폴더 상세")
data class PublicFolderDetailResponse(
    val folderId: Long,
    val name: String,
    @field:Schema(description = "폴더 안 위시. 카드 값은 내 위시 목록과 같은 계산이다")
    val wishes: List<PublicWishResponse>,
) {
    companion object {
        fun from(detail: PublicFolderDetail): PublicFolderDetailResponse =
            PublicFolderDetailResponse(
                folderId = detail.folderId,
                name = detail.name,
                // 카드 값은 내 위시 목록과 같은 뷰(ItemView)로 내려 한 모양을 유지한다
                wishes =
                    detail.wishes.map {
                        PublicWishResponse(wishId = it.wish.getId(), item = WishItemResponse.ItemView.from(it))
                    },
            )
    }
}

@Schema(description = "공개 폴더 안의 위시 한 장")
data class PublicWishResponse(
    val wishId: Long,
    @field:Schema(description = "화면값. 내 위시 목록과 같은 모양이다. 주인의 폴더 구성은 담지 않는다")
    val item: WishItemResponse.ItemView,
)
