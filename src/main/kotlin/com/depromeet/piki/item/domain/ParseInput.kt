package com.depromeet.piki.item.domain

import com.depromeet.piki.product.domain.ProductLink

data class ParseInput(
    val type: ParseInputType,
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "파싱 입력 값은 비어 있을 수 없다" }
    }

    fun linkOrNull(): ProductLink? = value.takeIf { type == ParseInputType.LINK }?.let(ProductLink::parse)

    fun toLink(): ProductLink = linkOrNull() ?: error("$type 입력은 링크가 아니다")

    companion object {
        fun ofLink(link: ProductLink): ParseInput = ParseInput(ParseInputType.LINK, link.toString())

        fun ofImage(key: String): ParseInput = ParseInput(ParseInputType.IMAGE, key)
    }
}
