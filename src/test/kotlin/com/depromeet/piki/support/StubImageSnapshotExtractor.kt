package com.depromeet.piki.support

import com.depromeet.piki.image.service.ImageSnapshotExtractor
import com.depromeet.piki.product.service.ProductSnapshot

class StubImageSnapshotExtractor : ImageSnapshotExtractor {
    var build: (String) -> ProductSnapshot = { notStubbed("build") }

    override fun extract(imageKey: String): ProductSnapshot = build(imageKey)
}
