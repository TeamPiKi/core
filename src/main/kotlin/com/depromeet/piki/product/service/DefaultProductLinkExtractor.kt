package com.depromeet.piki.product.service

import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.gemini.GeminiHtmlExtractor
import com.depromeet.piki.product.service.structured.StructuredDataExtractor
import com.depromeet.piki.product.service.structured.StructuredExtraction
import io.micrometer.core.instrument.MeterRegistry
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

// 정적 HTTP fetch 기반 추출 "전략"(LinkExtractionStrategy)이다. fetch 를 1회 수행하고, 구조화 데이터(JSON-LD/OpenGraph)로
// 먼저 파싱한다. 필수 필드가 검증을 통과하면 LLM 을 건너뛰고, 미달이면 같은 HTML 을 Gemini 로 넘겨 추출한다(재fetch 없음).
// 공개 진입점은 FallbackProductLinkExtractor(ProductLinkExtractor 구현)이고, 이 빈은 그 "plain" 전략으로 주입된다 —
// AsyncItemParsingWorker·통합 테스트 stub 은 여전히 진입점(ProductLinkExtractor)만 보므로 호출·격리 구조가 그대로 유지된다.
//
// 추출 방법을 product.extract 카운터로 집계한다 — via=structured(직접 파싱) 대 via=llm(LLM fallback) 의 비율이
// 곧 비싼 LLM 호출을 얼마나 줄였는지의 비용 지표다. fallback 은 reason 라벨로 사유(no_data/missing_field/invalid_value)를
// 분해해 "직접 파싱 적중률을 올리려면 어디를 보강할지"를 본다. application 태그는 management.metrics.tags 가 자동 부착한다.
@Component(LinkExtractionStrategy.PLAIN)
class DefaultProductLinkExtractor(
    private val pageFetcher: PageFetcher,
    private val structuredDataExtractor: StructuredDataExtractor,
    private val geminiHtmlExtractor: GeminiHtmlExtractor,
    private val meterRegistry: MeterRegistry,
) : LinkExtractionStrategy {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun extract(link: ProductLink): ProductSnapshot {
        val fetchStart = System.nanoTime()
        val page = pageFetcher.fetch(link)
        val fetchMs = (System.nanoTime() - fetchStart) / 1_000_000

        // HTML 을 한 번만 파싱해 구조화 파서와 Gemini fallback 이 같은 Document 를 공유한다(파싱·ld+json 식별 중복 제거).
        // baseUri 는 html 의 출처인 최종 URL(page.finalUrl) 기준 — redirect 를 따라갔으면 원본 link 와 host 가 다를 수 있다.
        val document = Jsoup.parse(page.html, page.finalUrl.value.toString())

        val result = structuredDataExtractor.extract(document, link)

        // 카운터는 한 곳에서 항상 {via, reason} 두 키로 발행한다 — 경로마다 태그 키가 갈라지면 Prometheus 가
        // 같은 메트릭 이름의 뒤 시계열을 조용히 드롭하므로(라벨 키 집합 불일치), 키를 단일 지점에서 통일한다.
        // Miss 일 때 LLM 호출 전에 올려, LLM 이 실패해도 "직접 파싱으로 못 끝내 LLM 에 의존한 비율"에 포함되게 한다.
        val (via, reason) =
            when (result) {
                is StructuredExtraction.Extracted -> VIA_STRUCTURED to REASON_NONE
                is StructuredExtraction.Miss -> VIA_LLM to result.reason
            }
        meterRegistry.counter(EXTRACT_METRIC, TAG_VIA, via, TAG_REASON, reason).increment()

        return when (result) {
            is StructuredExtraction.Extracted -> {
                log.info(
                    "extract via=structured fetch={}ms html={}chars url={}",
                    fetchMs,
                    page.html.length,
                    link.safeLogString(),
                )
                result.snapshot
            }

            is StructuredExtraction.Miss -> {
                val llmStart = System.nanoTime()
                val snapshot = geminiHtmlExtractor.extract(document, link)
                val llmMs = (System.nanoTime() - llmStart) / 1_000_000
                log.info(
                    "extract via=llm reason={} fetch={}ms llm={}ms html={}chars url={}",
                    result.reason,
                    fetchMs,
                    llmMs,
                    page.html.length,
                    link.safeLogString(),
                )
                snapshot
            }
        }
    }

    companion object {
        private const val EXTRACT_METRIC = "product.extract"
        private const val TAG_VIA = "via"
        private const val TAG_REASON = "reason"
        private const val VIA_STRUCTURED = "structured"
        private const val VIA_LLM = "llm"
        private const val REASON_NONE = "none"
    }
}
