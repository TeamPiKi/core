package com.depromeet.piki.support

import com.depromeet.piki.product.service.remote.ExtractionModelProbe
import com.depromeet.piki.product.service.remote.ExtractionTarget

class StubExtractionModelProbe : ExtractionModelProbe {
    var behavior: (ExtractionTarget, String) -> Unit = { _, _ -> notStubbed("behavior") }

    val calls: MutableList<Pair<ExtractionTarget, String>> = mutableListOf()

    override fun verify(
        target: ExtractionTarget,
        model: String,
    ) {
        calls += target to model
        behavior(target, model)
    }
}
