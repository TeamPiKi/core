package com.depromeet.piki.item.domain

import com.depromeet.piki.image.domain.ProductImageKey
import com.depromeet.piki.product.domain.ParseInputType
import com.depromeet.piki.product.domain.ProductLink

// 파싱 요청의 입력. 저장은 type 과 value 두 칸이고 코드 안에서는 링크·이미지 키 타입으로 다룬다
sealed interface ParseInput {
    val type: ParseInputType
    val value: String

    data class Link(
        val link: ProductLink,
    ) : ParseInput {
        override val type: ParseInputType get() = ParseInputType.LINK
        override val value: String get() = link.toString()
    }

    data class Image(
        val key: ProductImageKey,
    ) : ParseInput {
        override val type: ParseInputType get() = ParseInputType.IMAGE
        override val value: String get() = key.value
    }

    companion object {
        // 저장된 두 칸에서 되살린다
        fun of(
            type: ParseInputType,
            value: String,
        ): ParseInput =
            when (type) {
                ParseInputType.LINK -> Link(ProductLink.parse(value))
                ParseInputType.IMAGE -> Image(ProductImageKey.parse(value))
            }
    }
}
