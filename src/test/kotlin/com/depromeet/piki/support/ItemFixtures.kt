package com.depromeet.piki.support

import com.depromeet.piki.item.domain.CanonicalLink
import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.product.domain.ProductLink

// 링크로 확정된 아이템. 정체 URL 이 있어 새로고침할 수 있다
fun linkItem(url: String): Item = Item(canonicalUrlOf(url))

// 이미지로 확정된 아이템. 정체 URL 이 없다
fun imageItem(): Item = Item(canonicalUrl = null)

fun canonicalUrlOf(url: String): String = CanonicalLink.of(ProductLink.parse(url)).url
