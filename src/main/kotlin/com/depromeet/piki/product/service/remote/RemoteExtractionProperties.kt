package com.depromeet.piki.product.service.remote

import org.springframework.boot.context.properties.ConfigurationProperties

// 원격 추출 서비스(PIKI-Extractor) 라우팅 설정. @ConfigurationPropertiesScan(PikiApplication)으로 자동 등록된다.
//
// 점진 전환용 스위치다: enabled=false(기본)면 RoutingProductLinkExtractor 빈 자체가 뜨지 않아
// 기존 진입점(FallbackProductLinkExtractor)이 그대로 쓰인다 — 현행과 완전 동일(zero-diff).
// 전환이 끝나면(이관 8단계) 이 설정과 embedded 경로를 함께 제거한다.
@ConfigurationProperties(prefix = "product.extract.remote")
data class RemoteExtractionProperties(
    // 원격 추출 라우팅 스위치. 켜면 RoutingProductLinkExtractor 가 @Primary 진입점이 된다.
    val enabled: Boolean = false,
    // PIKI-Extractor base URL (예: http://10.0.x.x:8090). enabled=true 면 필수.
    val baseUrl: String = "",
    // 원격으로 보낼 host 목록(도메인 단위 suffix 매칭, 서브도메인 포함). 비어 있으면 전량 원격.
    // 점진 전환: 처음엔 소수 host 만 넣고 관측하며 넓힌다. 롤백은 enabled=false 한 줄.
    val hosts: List<String> = emptyList(),
    val connectTimeoutMs: Int = 2_000,
    // 호출자 stale 판정(ItemParsingScheduler.STALE_TIMEOUT=60s)보다 항상 작아야 recover 의 유령 중복 발주가 없다.
    // extractor 내부 예산(fetch 15s + Gemini 30s + 여유)의 바깥 상한 — 계약 문서(extractor repo docs/api-contract.md §3)와 함께 갱신한다.
    val readTimeoutMs: Int = 55_000,
) {
    init {
        if (enabled) {
            require(baseUrl.isNotBlank()) { "product.extract.remote.enabled=true 면 base-url 이 필요하다." }
        }
        require(readTimeoutMs < STALE_TIMEOUT_MS) {
            "read-timeout($readTimeoutMs ms)은 outbox stale 판정(60s)보다 작아야 한다 — recover 유령 중복 발주 방지."
        }
    }

    companion object {
        // ItemParsingScheduler.STALE_TIMEOUT 과 같은 값. 스케줄러 상수를 직접 참조하면 순환 의존이라 값을 복제하고
        // 주석으로 결속한다 — 스케줄러 쪽 값을 바꾸면 여기도 함께 본다.
        private const val STALE_TIMEOUT_MS = 60_000
    }
}
