package com.depromeet.piki.support

import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.ProductSnapshot

class StubLinkSnapshotExtractor {
    var build: (ProductLink) -> ProductSnapshot = { notStubbed("build") }

    fun extract(link: ProductLink): ProductSnapshot = build(link)
}
