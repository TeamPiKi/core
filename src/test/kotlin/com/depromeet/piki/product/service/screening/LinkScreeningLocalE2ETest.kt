package com.depromeet.piki.product.service.screening

import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.http.HttpPageFetcher
import com.depromeet.piki.product.service.http.PageFetchException
import com.depromeet.piki.product.service.http.PageFetchHttpClientConfig
import com.depromeet.piki.product.service.http.RequestScopedDnsResolver
import com.depromeet.piki.product.service.structured.StructuredDataExtractor
import com.depromeet.piki.product.service.structured.StructuredExtraction
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 1단계 로컬 스크리닝 (일회성 측정 도구, 회귀 테스트 아님).
 *
 * 내 로컬에서 운영 fetch(HttpPageFetcher + 운영 RestClient) + 구조화 파서(JSON-LD/OG)까지만 돌려, URL 하나를
 * 다음 다섯 갈래로 가른다:
 *   PARSE-FAIL            URL 형식 자체가 깨짐(https 외 스킴 등)
 *   UNSUPPORTED(정책차단)  등록 경계 미지원 정책(ExtractionRoutingPolicy, DB) — dev 라면 400 으로 떨어질 URL
 *   BLOCKED/FETCH-FAIL    fetch 단계 봇 차단·접근 실패(403/500/timeout 등)
 *   PARSER-OK             JSON-LD/OG 로 name+price 추출 성공 (Gemini 불필요)
 *   PARSER-MISS(...)      fetch 는 됐으나 구조화 파싱 실패 → 실제 추출 여부는 2단계 dev(Gemini)가 답한다
 *
 * Gemini 는 호출하지 않으므로 API 키·비용·Docker·Spring 컨텍스트가 없다 — 빠르고 부수효과가 없다.
 *
 * 입력은 SCREENING_INPUT 이 가리키는 JSON(플랫폼별 PC·모바일 묶음, ScreeningInput 참고). 이 환경변수가
 * 격리 게이트라 미설정 시(CI 포함) 통째로 skip 된다.
 *
 * 실행: SCREENING_INPUT=/path/to/urls.json ./gradlew test --tests "*LinkScreeningLocalE2ETest" -i
 * 결과는 stdout 과 /tmp/link-screening-local.txt 양쪽에 남는다.
 */
@EnabledIfEnvironmentVariable(named = ScreeningInput.ENV, matches = ".+")
class LinkScreeningLocalE2ETest {
    private val dnsResolver = RequestScopedDnsResolver()
    private val pageFetcher =
        HttpPageFetcher(
            PageFetchHttpClientConfig().pageFetchRestClient(ObservationRegistry.NOOP, dnsResolver),
            dnsResolver,
        )
    private val structured = StructuredDataExtractor(jacksonObjectMapper())

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    fun `PC·모바일 링크 1차 로컬 스크리닝 - 정책차단 fetch차단 파서 Gemini필요 판정`() {
        val sb = StringBuilder()
        ScreeningInput.load().forEach { target ->
            sb.appendLine("=== ${target.platform} ===")
            sb.appendLine("  [PC 직링크]")
            target.pc.forEach { sb.appendLine("    " + screen(it)) }
            sb.appendLine("  [모바일 공유링크]")
            target.mobile.forEach { sb.appendLine("    " + screen(it)) }
            sb.appendLine()
        }
        val report = sb.toString()
        println(report)
        File("/tmp/link-screening-local.txt").writeText(report)
    }

    private fun screen(url: String): String {
        val link =
            try {
                ProductLink.parse(url)
            } catch (e: Exception) {
                return "PARSE-FAIL            ${tail(url)}  ${e.javaClass.simpleName}: ${e.message}"
            }
        // 등록 경계의 정책 차단(미지원 쇼핑몰)을 로컬에서도 재현한다 — dev 라면 등록 시점 400 으로 떨어질 URL.
        // 실제 판정은 DB 정책(ExtractionRoutingPolicy)이지만 이 E2E 는 Spring 없이 돌므로 시드 목록 사본으로 재현한다
        // (dev 정책이 바뀌면 이 목록도 손으로 맞춘다 — 스크리닝 참고용이라 어긋나도 해는 낮다).
        if (link.matchesAnyDomain(UNSUPPORTED_SEED)) {
            return "UNSUPPORTED(정책차단)  ${tail(url)}  아직 지원하지 않는 쇼핑몰이에요."
        }
        val page =
            try {
                pageFetcher.fetch(link)
            } catch (e: Exception) {
                val detail =
                    when (e) {
                        is PageFetchException ->
                            "category=${e.category} status=${e.httpStatus.value()} " +
                                "cause=${e.cause?.javaClass?.simpleName}: ${e.cause?.message?.take(140)}"
                        else -> "${e.javaClass.simpleName}: ${e.message}"
                    }
                return "BLOCKED/FETCH-FAIL    ${tail(url)}  $detail"
            }
        return when (val r = structured.extract(page)) {
            is StructuredExtraction.Extracted ->
                "PARSER-OK             ${tail(url)}  name=${r.snapshot.name}  price=${r.snapshot.currentPrice}  " +
                    "htmlLen=${page.html.length}  final=${page.finalUrl.value.host}"
            is StructuredExtraction.Miss ->
                "PARSER-MISS(${r.reason}) Gemini필요  ${tail(url)}  htmlLen=${page.html.length}  final=${page.finalUrl.value.host}"
        }
    }

    // 로그 가독성용으로 host+path 의 마지막 식별자만 짧게 보여준다(쿼리스트링 제외).
    private fun tail(url: String): String = url.substringBefore("?").takeLast(40)

    companion object {
        // dev 미지원 정책 시드의 사본(V20260707064747 마이그레이션) — Spring 없이 도는 E2E 라 DB 정책을 못 읽는다.
        private val UNSUPPORTED_SEED =
            setOf("kream.co.kr", "coupang.com", "naver.com", "naver.me", "oliveyoung.co.kr", "oy.run", "a-bly.com")
    }
}
