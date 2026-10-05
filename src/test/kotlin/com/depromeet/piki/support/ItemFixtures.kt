package com.depromeet.piki.support

import com.depromeet.piki.item.domain.CanonicalLink
import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.product.domain.ProductLink
import java.util.UUID

fun linkItem(url: String): Item = Item.ofLink(CanonicalLink.of(ProductLink.parse(url)))

fun imageItem(): Item = Item.ofImage("items/raw/${UUID.randomUUID()}.png")

fun canonicalUrlOf(url: String): String = CanonicalLink.of(ProductLink.parse(url)).url
