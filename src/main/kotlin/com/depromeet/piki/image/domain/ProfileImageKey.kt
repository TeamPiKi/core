package com.depromeet.piki.image.domain

import java.util.UUID

// 프로필 사진 업로드의 raw 키. 확정 때 사용자 접두 아래로 옮겨 심고 지운다
@JvmInline
value class ProfileImageKey private constructor(
    override val value: String,
) : RawImageKey {
    override fun toString(): String = value

    companion object {
        private const val PREFIX = "profiles/raw/"
        private val FORMAT = RawImageKey.formatOf(PREFIX)

        fun newRaw(extension: String): ProfileImageKey = ProfileImageKey("$PREFIX${UUID.randomUUID()}.$extension")

        // 클라이언트가 돌려준 키. 발급 형식이 아니면 우리 객체가 아니다
        fun parse(raw: String): ProfileImageKey {
            if (!FORMAT.matches(raw)) throw ImageUploadException.invalidKey()
            return ProfileImageKey(raw)
        }
    }
}
