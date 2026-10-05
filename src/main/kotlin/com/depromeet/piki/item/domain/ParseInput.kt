package com.depromeet.piki.item.domain

import com.depromeet.piki.product.domain.ProductLink

sealed interface ParseInput {
    data class Link(
        val link: ProductLink,
    ) : ParseInput

    data class Image(
        val key: String,
    ) : ParseInput
}
