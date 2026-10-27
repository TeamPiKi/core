package com.depromeet.piki.image.domain

// presign 으로 클라이언트가 직접 올린 객체의 키. 용도마다 접두가 달라 서로 섞이지 않는다
sealed interface UploadedImageKey {
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
