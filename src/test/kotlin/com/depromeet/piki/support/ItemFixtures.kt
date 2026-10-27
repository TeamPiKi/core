package com.depromeet.piki.support

import com.depromeet.piki.image.domain.ProductImageKey
import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.NormalizedInput
import com.depromeet.piki.item.domain.ParseInput
import com.depromeet.piki.product.domain.ProductLink

fun linkItem(url: String): Item = itemOf(ParseInput.Link(ProductLink.parse(url)))

fun imageItem(): Item = itemOf(ParseInput.Image(ProductImageKey.newRaw("png")))

fun normalizedOf(url: String): NormalizedInput = requireNotNull(NormalizedInput.of(ParseInput.Link(ProductLink.parse(url))))

private fun itemOf(input: ParseInput): Item = Item.of(input.type, requireNotNull(NormalizedInput.of(input)).asCanonical())
