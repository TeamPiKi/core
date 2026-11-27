package com.depromeet.piki.profile.controller.dto

import com.depromeet.piki.profile.domain.ProfileRelation
import com.depromeet.piki.profile.service.dto.PublicFolderDetail
import com.depromeet.piki.profile.service.dto.PublicFolderSummary
import com.depromeet.piki.profile.service.dto.PublicProfile
import com.depromeet.piki.wishlist.controller.dto.WishItemResponse
import io.swagger.v3.oas.annotations.media.ArraySchema
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
    @field:Schema(
        description =
            "보는 사람이 이 사람의 폴더를 볼 수 없으면 true(프로필 비공개 · 차단 관계). " +
                "공개한 폴더가 0개인 경우는 false 라 두 화면을 가를 수 있다. 상대가 나를 차단한 경우는 비공개 프로필과 같은 true",
        example = "false",
    )
    val foldersHidden: Boolean,
    @field:Schema(description = "공개 폴더. 볼 수 없으면 빈 배열 — 가려졌는지는 foldersHidden 으로 가른다")
    val folders: List<PublicFolderResponse>,
) {
    companion object {
        fun from(profile: PublicProfile): PublicProfileResponse =
            PublicProfileResponse(
                userId = profile.userId,
                nickname = profile.nickname,
                profileImage = profile.profileImage,
                relation = profile.relation,
                foldersHidden = profile.foldersHidden,
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
    @field:ArraySchema(
        arraySchema =
            Schema(
                description =
                    "표지 칸. 내 폴더 목록과 같은 규칙으로 최근 넣은 위시 최대 3개의 이미지 URL 을 그 순서로 준다. " +
                        "이미지가 없는 위시의 칸은 null 이고 앱이 기본 이미지로 채운다. 폴더가 비면 빈 배열",
                example = "[\"https://cdn.example.com/p/701.jpg\", null, \"https://cdn.example.com/p/690.jpg\"]",
            ),
        schema = Schema(type = "string", nullable = true),
    )
    val coverImageUrls: List<String?>,
) {
    companion object {
        fun from(folder: PublicFolderSummary): PublicFolderResponse =
            PublicFolderResponse(
                folderId = folder.folderId,
                name = folder.name,
                wishCount = folder.wishCount,
                coverImageUrls = folder.coverImageUrls,
            )
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
                        val savedWishId = detail.savedWishIdOf(it)
                        PublicWishResponse(
                            wishId = it.wish.getId(),
                            saved = savedWishId?.let { true } ?: false,
                            savedWishId = savedWishId,
                            item = WishItemResponse.ItemView.from(it),
                        )
                    },
            )
    }
}

@Schema(description = "공개 폴더 안의 위시 한 장")
data class PublicWishResponse(
    val wishId: Long,
    @field:Schema(
        description = "보는 사람이 이미 내 위시에 가진 상품인지. 탐색 카드의 saved 와 같은 뜻이고, 비로그인·게스트는 false",
        example = "false",
    )
    val saved: Boolean,
    @field:Schema(
        description = "보는 사람이 이 상품을 담은 내 위시의 id. 채워진 하트를 다시 누르면 이 id 로 위시 삭제(DELETE /api/v1/wishlists/{wishId})를 부른다. saved 가 false 면 null",
        example = "1900",
        nullable = true,
    )
    val savedWishId: Long?,
    @field:Schema(description = "화면값. 내 위시 목록과 같은 모양이다. 주인의 폴더 구성은 담지 않는다")
    val item: WishItemResponse.ItemView,
)
