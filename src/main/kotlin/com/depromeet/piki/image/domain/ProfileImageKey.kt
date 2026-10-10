package com.depromeet.piki.image.domain

import java.util.UUID

// 프로필 사진 업로드 키. 처음부터 사용자 접두 아래라 탈퇴 때 접두째 지워진다
@JvmInline
value class ProfileImageKey private constructor(
    override val value: String,
) : UploadedImageKey {
    override fun toString(): String = value

    companion object {
        private const val PREFIX = "profiles/"

        fun prefixOf(owner: UUID): String = "$PREFIX$owner/"

        fun newFor(
            owner: UUID,
            extension: String,
        ): ProfileImageKey = ProfileImageKey("${prefixOf(owner)}${UUID.randomUUID()}.$extension")

        // 주인까지 맞춘다. 남이 올린 사진 키로 자기 프로필을 바꾸지 못하게
        fun parse(
            raw: String,
            owner: UUID,
        ): ProfileImageKey {
            if (!UploadedImageKey.formatOf(prefixOf(owner)).matches(raw)) throw ImageUploadException.invalidKey()
            return ProfileImageKey(raw)
        }
    }
}
