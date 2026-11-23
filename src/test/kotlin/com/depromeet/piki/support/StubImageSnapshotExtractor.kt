package com.depromeet.piki.support

import com.depromeet.piki.image.domain.ProductImageKey
import com.depromeet.piki.product.service.ProductSnapshot

class StubImageSnapshotExtractor {
    var build: (ProductImageKey) -> ProductSnapshot = { notStubbed("build") }

    fun extract(key: ProductImageKey): ProductSnapshot = build(key)
}
