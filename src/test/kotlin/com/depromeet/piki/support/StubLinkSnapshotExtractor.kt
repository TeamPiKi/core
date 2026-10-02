package com.depromeet.piki.support

import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.LinkSnapshotExtractor
import com.depromeet.piki.product.service.ProductSnapshot

class StubLinkSnapshotExtractor : LinkSnapshotExtractor {
    var build: (ProductLink) -> ProductSnapshot = { notStubbed("build") }

    override fun extract(link: ProductLink): ProductSnapshot = build(link)
}
