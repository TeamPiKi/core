package com.depromeet.piki.product.service

import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.http.PageFetchException
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

// 상품 URL 추출의 공개 진입점(ProductLinkExtractor). 두 전략을 "우리 먼저, 막히면 헤드리스" 공식으로 엮는다:
//   1) plain(DefaultProductLinkExtractor)   : 정적 HTTP + 구조화/LLM. 싸고 빠른 기본 경로.
//   2) headless(HeadlessProductLinkExtractor): 차단 우회 브라우저. 비싸고 느려, plain 이 "차단" 으로 막힌 경우에만 탄다.
//
// 이 클래스는 seam(골격)이다 — 헤드리스 소비자(piki-scraper)가 아직 없으므로 product.extract.headless.enabled 기본값이
// false 이고, 그동안은 plain 에 그대로 위임해 현재 동작과 100% 동일하다(behavior-neutral). 소비자가 붙으면
// HeadlessProductLinkExtractor 를 구현하고 이 플래그만 켜면 된다.
//
// 중요: 에스컬레이션 축(plain 차단 → headless)은 outbox 재시도 축(일시 오류 → 같은 plain 재시도, #461)과 직교한다.
// 차단은 재시도 축에서 이미 "즉시 FAILED(영구)" 라 그 슬롯(attemptCount)에 얹을 수 없다. 그래서 여기서 별도로 판정한다.
@Component
class FallbackProductLinkExtractor(
    @Qualifier(LinkExtractionStrategy.PLAIN) private val plain: LinkExtractionStrategy,
    @Qualifier(LinkExtractionStrategy.HEADLESS) private val headless: LinkExtractionStrategy,
    private val meterRegistry: MeterRegistry,
    private val headlessProperties: HeadlessExtractionProperties,
) : ProductLinkExtractor {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun extract(link: ProductLink): ProductSnapshot {
        // 헤드리스가 꺼져 있으면(기본값) plain 에 그대로 위임한다 — 결과도 예외도 그대로 전파돼 현재 동작과 동일하다.
        if (!headlessProperties.enabled) return plain.extract(link)

        // (확장 지점) 호출량 x 느린-실패 낭비가 큰 host 는 여기서 plain 을 건너뛰고 headless 로 직행하되, 소량(canary)만
        // plain 으로 흘려 차단 해제를 감시한다. host 별 정책·canary 비율은 헤드리스 구현·운영 메트릭이 확정할 때 붙인다.

        return try {
            plain.extract(link)
        } catch (e: Exception) {
            if (!shouldEscalate(e)) throw e
            escalateToHeadless(link)
        }
    }

    // plain 이 차단으로 막혀 headless 로 넘긴다. 결과를 outcome 으로 집계한다 — "무조건 폴백" 이라 escalate 를 넓게 트는 대신,
    // "폴백했는데도 못 가져온"(headless 마저 실패) 비율이 낭비·한계 지표이자 후속 per-host 튜닝의 근거다. 어느 host 가
    // 헤드리스로도 안 되는지는 로그(host)로 드릴다운한다(메트릭=추세, 로그=원장). headless 예외는 그대로 상위로 전파해
    // 워커의 재시도/종결 판정에 맡긴다.
    private fun escalateToHeadless(link: ProductLink): ProductSnapshot {
        log.info("extract escalate=headless url={}", link.safeLogString())
        return try {
            headless.extract(link).also {
                meterRegistry.counter(ESCALATION_METRIC, TAG_OUTCOME, OUTCOME_SUCCESS).increment()
            }
        } catch (headlessFailure: Exception) {
            meterRegistry.counter(ESCALATION_METRIC, TAG_OUTCOME, OUTCOME_FAILED).increment()
            log.warn(
                "extract escalate=headless outcome=failed cause={} url={}",
                headlessFailure::class.simpleName,
                link.safeLogString(),
            )
            throw headlessFailure
        }
    }

    // plain 실패가 "차단이라 headless 면 뚫릴 수 있음" 인지 판정한다. 어떤 fetch 실패가 그런지는 PageFetchException.escalatable
    // 이 단일 진실이다(무조건 폴백: 영구 실패는 전부 true, SSRF 와 일시 오류만 false). SSRF 로 우리가 막은 host(blockedHost)는
    // escalatable=false 라 절대 여기로 오지 않는다(뚫어선 안 되는 내부망).
    private fun shouldEscalate(e: Throwable): Boolean = (e as? PageFetchException)?.escalatable == true

    companion object {
        private const val ESCALATION_METRIC = "product.extract.escalation"
        private const val TAG_OUTCOME = "outcome"
        private const val OUTCOME_SUCCESS = "success"
        private const val OUTCOME_FAILED = "failed"
    }
}
