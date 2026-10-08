package com.depromeet.piki.item.domain

import com.depromeet.piki.product.domain.ProductLink

// 아이템의 정체 값. 귀결점(없으면 등록 입력)을 정규화한 값이라 등록 입력의 정규화 값과 다를 수 있다
@JvmInline
value class CanonicalInput(
    val value: String,
) {
    override fun toString(): String = value

    // 링크 아이템의 정체 값은 그 자체가 상품 주소다. 이미지 아이템에서는 부르지 않는다
    fun toLink(): ProductLink = ProductLink.parse(value)

    // 정체 값을 그대로 다시 파싱 입력으로 쓴다
    fun toParseInput(type: ParseInputType): ParseInput = ParseInput.of(type, value)
}
