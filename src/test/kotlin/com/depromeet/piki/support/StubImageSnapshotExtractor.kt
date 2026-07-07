package com.depromeet.piki.support

import com.depromeet.piki.image.service.ImageSnapshotExtractor
import com.depromeet.piki.product.service.ProductSnapshot

// 이미지 파싱 외부 경계(원격 PIKI-Extractor 호출)를 통합 테스트에서 격리하는 stub —
// 링크의 StubProductLinkExtractor 와 같은 자리(진입점 경계)다. 매 테스트가 본문에서 build 람다를 명시 세팅한다.
//
// default build 는 throw 다. 명시 세팅을 빠뜨리면 즉시 IllegalStateException 으로 깨져
// "이전 테스트의 build 가 살아남아 다음 테스트에 영향을 주는" 함정을 차단한다.
class StubImageSnapshotExtractor : ImageSnapshotExtractor {
    var build: (String) -> ProductSnapshot = {
        error("stub.build 를 테스트 본문에서 명시 세팅해야 한다. CLAUDE.md '테스트 셋업 원칙' 참고.")
    }

    override fun extract(imageKey: String): ProductSnapshot = build(imageKey)
}
