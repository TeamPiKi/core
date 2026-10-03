package com.depromeet.piki.product.service

import com.depromeet.piki.product.domain.ProductLink

interface LinkSnapshotExtractor {
    fun extract(link: ProductLink): ProductSnapshot
}
