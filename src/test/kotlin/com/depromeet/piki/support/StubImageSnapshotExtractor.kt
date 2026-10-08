package com.depromeet.piki.support

import com.depromeet.piki.image.domain.ImageKey
import com.depromeet.piki.image.service.ImageSnapshotExtractor
import com.depromeet.piki.product.service.ProductSnapshot

class StubImageSnapshotExtractor : ImageSnapshotExtractor {
    var build: (ImageKey) -> ProductSnapshot = { notStubbed("build") }

    override fun extract(key: ImageKey): ProductSnapshot = build(key)
}
