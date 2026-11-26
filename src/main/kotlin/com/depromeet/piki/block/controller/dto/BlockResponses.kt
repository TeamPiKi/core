package com.depromeet.piki.block.controller.dto

import com.depromeet.piki.block.service.dto.BlockList
import com.depromeet.piki.block.service.dto.BlockedUser
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

@Schema(description = "내가 차단한 사람 목록")
data class BlockListResponse(
    @field:Schema(description = "최근 차단순")
    val blocked: List<BlockedUserResponse>,
) {
    companion object {
        fun from(result: BlockList): BlockListResponse =
            BlockListResponse(
                blocked = result.blocked.map { BlockedUserResponse.from(it) },
            )
    }
}

@Schema(description = "차단 목록 한 행")
data class BlockedUserResponse(
    @field:Schema(description = "차단된 사용자 id")
    val userId: UUID,
    @field:Schema(description = "닉네임. 항상 공개된다", example = "난폭한 북극곰")
    val nickname: String,
    @field:Schema(description = "프로필 이미지 URL. 항상 공개된다")
    val profileImage: String,
) {
    companion object {
        fun from(blocked: BlockedUser): BlockedUserResponse =
            BlockedUserResponse(
                userId = blocked.userId,
                nickname = blocked.nickname,
                profileImage = blocked.profileImage,
            )
    }
}
