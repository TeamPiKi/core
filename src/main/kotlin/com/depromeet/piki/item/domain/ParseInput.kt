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

    // 저장 상한을 넘는 값은 정체 값이 될 수 없다. 절단은 다른 상품과 충돌할 수 있는 거짓 정체성이라 쓰지 않는다(#843)
    fun normalizedOrNull(): NormalizedInput? = NormalizedInput.of(this).takeUnless { it.exceedsStorageLimit }

    companion object {
        fun ofLink(link: ProductLink): ParseInput = ParseInput(ParseInputType.LINK, link.toString())

        fun ofImage(key: String): ParseInput = ParseInput(ParseInputType.IMAGE, key)
    }
}
