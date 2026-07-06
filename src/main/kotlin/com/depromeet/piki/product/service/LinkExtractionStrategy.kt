package com.depromeet.piki.product.service

import com.depromeet.piki.product.domain.ProductLink

// 상품 URL 추출의 "한 전략". 현재 두 구현이 있다:
//   - DefaultProductLinkExtractor  : 정적 HTTP fetch + 구조화(JSON-LD/OG) 우선 + LLM fallback (싸고 빠른 기본 경로)
//   - HeadlessProductLinkExtractor : 차단 우회 헤드리스 브라우저 (차단 플랫폼용, 아직 미구현 placeholder)
//
// 공개 진입점(ProductLinkExtractor)과 분리한다: 워커·통합 테스트 stub 은 진입점만 알고, 전략이 늘거나 바뀌어도
// 영향받지 않는다. FallbackProductLinkExtractor(진입점 구현)가 이 전략들을 "plain 먼저, 막히면 headless" 로 엮는다.
interface LinkExtractionStrategy {
    fun extract(link: ProductLink): ProductSnapshot

    // 전략 빈의 명시 이름 = 단일 진실. FallbackProductLinkExtractor 가 @Qualifier 로 이 이름을 참조해 두 전략을
    // 정확히 주입한다. 이름을 클래스명 기본값(decapitalized)에 맡기면 클래스 rename 시 @Qualifier 문자열이 조용히
    // 어긋나 부팅에서만 깨지므로, 클래스명과 무관한 상수로 못박아 rename-safe + single-source 로 둔다.
    companion object {
        const val PLAIN = "plainLinkExtractionStrategy"
        const val HEADLESS = "headlessLinkExtractionStrategy"
    }
}
