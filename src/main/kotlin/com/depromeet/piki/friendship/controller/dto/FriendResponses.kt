package com.depromeet.piki.friendship.controller.dto

import com.depromeet.piki.friendship.service.dto.FriendList
import com.depromeet.piki.friendship.service.dto.FriendSummary
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

@Schema(description = "내 친구 목록")
data class FriendListResponse(
    @field:Schema(description = "친구 수. 이 화면에서만 노출된다", example = "8")
    val friendCount: Int,
    @field:Schema(description = "최근 추가순")
    val friends: List<FriendResponse>,
) {
    companion object {
        fun from(result: FriendList): FriendListResponse =
            FriendListResponse(
                friendCount = result.friendCount,
                friends = result.friends.map { FriendResponse.from(it) },
            )
    }
}

@Schema(description = "친구 목록 한 행")
data class FriendResponse(
    @field:Schema(description = "친구의 사용자 id")
    val userId: UUID,
    @field:Schema(description = "닉네임. 항상 공개된다", example = "난폭한 북극곰")
    val nickname: String,
    @field:Schema(description = "프로필 이미지 URL. 항상 공개된다")
    val profileImage: String,
    // 비공개면 두 수가 없다. 앱은 수 대신 비공개 표시를 띄운다 — 0 으로 내리면 "공개했는데 비어 있음" 과 구분이 안 된다
    @field:Schema(description = "공개 폴더 수. 상대가 프로필을 비공개로 두면 없음", example = "3")
    val folderCount: Long?,
    @field:Schema(description = "공개 폴더에 든 위시 수. 상대가 프로필을 비공개로 두면 없음", example = "12")
    val wishCount: Long?,
) {
    companion object {
        fun from(summary: FriendSummary): FriendResponse =
            FriendResponse(
                userId = summary.userId,
                nickname = summary.nickname,
                profileImage = summary.profileImage,
                folderCount = summary.counts?.folderCount,
                wishCount = summary.counts?.wishCount,
            )
    }
}
