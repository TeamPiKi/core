package com.depromeet.piki.image.domain

import java.util.UUID

// 상품 사진 업로드의 raw 키. 이미지 아이템의 정체 값이라 지우지 않는다
@JvmInline
value class ProductImageKey private constructor(
    override val value: String,
) : UploadedImageKey {
    override fun toString(): String = value

    companion object {
        private const val PREFIX = "items/raw/"
        private val FORMAT = UploadedImageKey.formatOf(PREFIX)

        fun newRaw(extension: String): ProductImageKey = ProductImageKey("$PREFIX${UUID.randomUUID()}.$extension")

        // 클라이언트가 돌려준 키. 발급 형식이 아니면 우리 객체가 아니다
        fun parse(raw: String): ProductImageKey {
            if (!FORMAT.matches(raw)) throw ImageUploadException.invalidKey()
            return ProductImageKey(raw)
        }
    }
}
