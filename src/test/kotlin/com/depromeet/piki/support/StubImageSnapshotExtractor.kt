package com.depromeet.piki.support

import com.depromeet.piki.image.service.ImageSnapshotExtractor
import com.depromeet.piki.product.service.ProductSnapshot

// 이미지 파싱 외부 경계(원격 extractor 호출)를 통합 테스트에서 격리하는 stub —
// 링크의 StubProductLinkExtractor 와 같은 자리(진입점 경계)다. 매 테스트가 본문에서 build 람다를 명시 세팅한다.
//
// default build 는 throw 다. 명시 세팅을 빠뜨리면 즉시 IllegalStateException 으로 깨져
// "이전 테스트의 build 가 살아남아 다음 테스트에 영향을 주는" 함정을 차단한다.
class StubImageSnapshotExtractor : ImageSnapshotExtractor {
    var build: (String) -> ProductSnapshot = {
        error("stub.build 를 테스트 본문에서 명시 세팅해야 한다. CLAUDE.md '테스트 셋업 원칙' 참고.")
    }

    override fun extract(imageKey: String): ProductSnapshot = build(imageKey)

    companion object {
        // 시나리오가 값 자체에 관심 없는 테스트용 기본 성공 스냅샷 — READY 불변식(name·price·imageUrl)을 채운다.
        // 스냅샷 필드가 바뀌면 여기 한 곳만 고치면 된다 (같은 모양 람다가 테스트마다 복붙되는 것을 막는다).
        fun defaultSnapshot(
            name: String = "상품",
            currentPrice: Int = 1_000,
            imageUrl: String = "https://img.example.com/p.png",
        ): ProductSnapshot = ProductSnapshot(link = null, name = name, currentPrice = currentPrice, currency = "KRW", imageUrl = imageUrl)
    }
}
