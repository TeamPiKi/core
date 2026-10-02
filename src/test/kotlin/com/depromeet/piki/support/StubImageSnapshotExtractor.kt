package com.depromeet.piki.support

import com.depromeet.piki.image.service.ImageSnapshotExtractor
import com.depromeet.piki.product.service.ProductSnapshot

class StubImageSnapshotExtractor : ImageSnapshotExtractor {
    var build: (String) -> ProductSnapshot = { notStubbed("build") }

    override fun extract(imageKey: String): ProductSnapshot = build(imageKey)

    companion object {
        fun defaultSnapshot(
            name: String = "상품",
            price: Int = 1_000,
            imageUrl: String = "https://img.example.com/p.png",
        ): ProductSnapshot = ProductSnapshot(link = null, name = name, price = price, currency = "KRW", imageUrl = imageUrl)
    }
}
