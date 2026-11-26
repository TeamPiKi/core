package com.depromeet.piki.support

import com.depromeet.piki.contracts.extraction.v1.ExtractionRule
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.remote.ExtractionRuleProbe
import com.depromeet.piki.product.service.remote.ExtractionRuleProbeResult

class StubExtractionRuleProbe : ExtractionRuleProbe {
    var behavior: (ProductLink, ExtractionRule) -> ExtractionRuleProbeResult = { _, _ -> notStubbed("behavior") }
    val calls: MutableList<Pair<ProductLink, ExtractionRule>> = mutableListOf()

    override fun probe(
        link: ProductLink,
        rule: ExtractionRule,
    ): ExtractionRuleProbeResult {
        calls += link to rule
        return behavior(link, rule)
    }
}
