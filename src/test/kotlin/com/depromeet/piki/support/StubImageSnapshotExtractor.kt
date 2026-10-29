package com.depromeet.piki.support

import com.depromeet.piki.image.domain.ProductImageKey
import com.depromeet.piki.image.service.ImageSnapshotExtractor
import com.depromeet.piki.product.service.ProductSnapshot

class StubImageSnapshotExtractor : ImageSnapshotExtractor {
    var build: (ProductImageKey) -> ProductSnapshot = { notStubbed("build") }

    override fun extract(key: ProductImageKey): ProductSnapshot = build(key)
}
