package com.depromeet.piki.image.domain

import java.util.UUID

// 이미지 아이템의 입력. presign 이 발급한 raw 객체 키만 받는다
@JvmInline
value class ImageKey private constructor(
    val value: String,
) {
    val extension: String get() = value.substringAfterLast('.')

    override fun toString(): String = value

    companion object {
        private const val RAW_PREFIX = "items/raw/"

        private val RAW_KEY_REGEX =
            Regex(
                "^${RAW_PREFIX}[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" +
                    "\\.(${ProductImage.EXTENSIONS.joinToString("|")})$",
            )

        fun newRaw(extension: String): ImageKey = ImageKey("$RAW_PREFIX${UUID.randomUUID()}.$extension")

        // 클라이언트가 돌려준 키. 발급 형식이 아니면 우리 객체가 아니다
        fun parse(raw: String): ImageKey {
            if (!RAW_KEY_REGEX.matches(raw)) throw ImageUploadException.invalidKey()
            return ImageKey(raw)
        }
    }
}
