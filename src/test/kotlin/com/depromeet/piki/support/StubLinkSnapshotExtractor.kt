package com.depromeet.piki.support

import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.LinkSnapshotExtractor
import com.depromeet.piki.product.service.ProductSnapshot

// 외부 LLM 호출을 통합 테스트에서 격리하기 위한 stub.
// 모든 통합 테스트가 같은 IntegrationTestSupport 컨텍스트를 공유하므로 이 빈도 단일 인스턴스다.
// 매 테스트가 본문에서 build 람다를 명시적으로 세팅한다 (셋업 hook · default 리셋 금지).
//
// default build 는 throw 다. 명시 세팅을 빠뜨리면 즉시 IllegalStateException 으로 깨져
// "이전 테스트의 build 가 살아남아 다음 테스트에 영향을 주는" 함정을 차단한다.
class StubLinkSnapshotExtractor : LinkSnapshotExtractor {
    var build: (ProductLink) -> ProductSnapshot = {
        error("stub.build 를 테스트 본문에서 명시 세팅해야 한다. CLAUDE.md '테스트 셋업 원칙' 참고.")
    }

    override fun extract(link: ProductLink): ProductSnapshot = build(link)
}
