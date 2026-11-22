package com.depromeet.piki.friendship.service.dto

import com.depromeet.piki.profile.service.ProfileCounts
import java.util.UUID

// 내 친구 목록 한 화면. 친구 수는 이 화면에서만 노출된다 — 다른 사람 프로필에는 넣지 않는다(#1285)
data class FriendList(
    val friendCount: Int,
    val friends: List<FriendSummary>,
)

// 목록 한 행. counts 가 null 이면 그 친구가 프로필을 비공개로 둔 것이고, 앱은 수 대신 비공개 표시를 띄운다
data class FriendSummary(
    val userId: UUID,
    val nickname: String,
    val profileImage: String,
    val counts: ProfileCounts?,
)
