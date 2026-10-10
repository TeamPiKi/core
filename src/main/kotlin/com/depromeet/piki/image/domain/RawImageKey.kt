package com.depromeet.piki.image.domain

// presign 이 발급한 raw 업로드 키. 용도마다 접두가 달라 서로 섞이지 않는다
sealed interface RawImageKey {
    val value: String

    val extension: String get() = value.substringAfterLast('.')

    companion object {
        fun formatOf(prefix: String): Regex =
            Regex(
                "^$prefix[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" +
                    "\\.(${ProductImage.EXTENSIONS.joinToString("|")})$",
            )
    }
}
