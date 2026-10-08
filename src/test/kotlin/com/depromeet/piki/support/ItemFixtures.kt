package com.depromeet.piki.support

import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.NormalizedInput
import com.depromeet.piki.item.domain.ParseInput
import com.depromeet.piki.product.domain.ProductLink
import java.util.UUID

fun linkItem(url: String): Item = itemOf(ParseInput.ofLink(ProductLink.parse(url)))

fun imageItem(): Item = itemOf(ParseInput.ofImage("items/raw/${UUID.randomUUID()}.png"))

fun normalizedOf(url: String): NormalizedInput = requireNotNull(NormalizedInput.of(ParseInput.ofLink(ProductLink.parse(url))))

private fun itemOf(input: ParseInput): Item = Item.of(input.type, requireNotNull(NormalizedInput.of(input)).asCanonical())
