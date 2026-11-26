package com.depromeet.piki.user.controller.dto

import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.User
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

// 마이페이지(GET /me) 전용 응답. 공유 UserResponse 와 달리 email 을 포함한다 — email 은 PII 라
// 로그인·게스트 생성 등 다른 응답엔 노출하지 않고, 본인이 자기 정보를 조회하는 이 경로에서만 내려준다.
@Schema(description = "내 정보 (마이페이지)")
data class MyProfileResponse(
    @field:Schema(description = "유저 식별자", format = "uuid")
    val id: UUID,
    @field:Schema(description = "닉네임 (최대 10자)", example = "뛰어다니는 강아지")
    val nickname: String,
    @field:Schema(description = "프로필 이미지 URL")
    val profileImage: String,
    @field:Schema(description = "유저 종류")
    val identityType: IdentityType,
    @field:Schema(
        description = "소셜 계정 email. 미수집(게스트)·미동의(애플 Private Relay 거부)·backfill 전 유저는 null.",
        nullable = true,
        example = "user@gmail.com",
    )
    val email: String?,
    @field:Schema(
        description = "내 프로필 공개 여부. false 면 폴더 공개 설정과 관계없이 다른 사람에게 폴더·위시가 보이지 않는다. 닉네임·프로필 이미지는 늘 보인다",
        example = "true",
    )
    val isProfilePublic: Boolean,
) {
    companion object {
        fun from(
            user: User,
            email: String?,
        ): MyProfileResponse =
            MyProfileResponse(
                id = user.id,
                nickname = user.nickname,
                profileImage = user.profileImage,
                identityType = user.identityType,
                email = email,
                isProfilePublic = user.isProfilePublic,
            )
    }
}
