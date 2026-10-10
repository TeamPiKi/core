package com.depromeet.piki.support

import com.depromeet.piki.product.domain.ParseInputType
import com.depromeet.piki.product.service.remote.ExtractionModelProbe

class StubExtractionModelProbe : ExtractionModelProbe {
    var behavior: (ParseInputType, String) -> Unit = { _, _ -> notStubbed("behavior") }

    val calls: MutableList<Pair<ParseInputType, String>> = mutableListOf()

    override fun verify(
        target: ParseInputType,
        model: String,
    ) {
        calls += target to model
        behavior(target, model)
    }
}
