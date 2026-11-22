package com.depromeet.piki.block.service.dto

import java.util.UUID

// 차단 목록 한 화면. 수는 내려보내지 않는다 — 기획 화면에 차단 수가 없다
data class BlockList(
    val blocked: List<BlockedUser>,
)

// 목록 한 행. 닉네임·프로필 이미지는 항상 공개라 차단 상대여도 보인다.
// 폴더·위시 수는 넣지 않는다 — 차단한 상대의 것은 볼 수 없다
data class BlockedUser(
    val userId: UUID,
    val nickname: String,
    val profileImage: String,
)
