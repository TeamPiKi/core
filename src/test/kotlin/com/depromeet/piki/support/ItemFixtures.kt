package com.depromeet.piki.support

import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.NormalizedInput
import com.depromeet.piki.item.domain.ParseInput
import com.depromeet.piki.product.domain.ProductLink
import java.util.UUID

fun linkItem(url: String): Item = itemOf(ParseInput.ofLink(ProductLink.parse(url)))

fun imageItem(): Item = itemOf(ParseInput.ofImage("items/raw/${UUID.randomUUID()}.png"))

fun canonicalUrlOf(url: String): String = NormalizedInput.of(ProductLink.parse(url)).value

private fun itemOf(input: ParseInput): Item = Item.of(input.type, NormalizedInput.of(input))
