package com.depromeet.piki.product.service.remote

import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.item.service.AsyncItemParsingWorker
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.routing.ExtractionRoute
import com.depromeet.piki.product.routing.ExtractionRoutingPolicy
import com.depromeet.piki.product.service.ProductSnapshotException
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 원격 추출 응답(계약 3갈래: 2xx / 422+code / 그 외)이 워커의 재시도 판정(category)과 정확히 맞물리게
// 번역되는지, 그리고 2xx 값이 경계 정규화(fromExtracted — 모든 추출 경로의 단일 진실 원천)를 거치는지 검증한다.
// 외부 경계(원격 HTTP)는 MockRestServiceServer 로 격리한다(HttpPageFetcher 테스트와 같은 방식).
class HttpProductLinkExtractorTest {
    private val link = ProductLink.parse("https://shop.example.com/p/1")

    private class FakeRoutingPolicy(private val route: ExtractionRoute? = null) : ExtractionRoutingPolicy {
        override fun routeOf(link: ProductLink): ExtractionRoute? = route
    }

    private fun extractorWith(
        route: ExtractionRoute? = null,
        server: (MockRestServiceServer) -> Unit,
    ): HttpProductLinkExtractor {
        val builder = RestClient.builder().baseUrl("http://extractor.test")
        val mockServer = MockRestServiceServer.bindTo(builder).build()
        server(mockServer)
        return HttpProductLinkExtractor(builder.build(), FakeRoutingPolicy(route))
    }

    @Test
    fun `200 응답의 추출 결과를 ProductSnapshot 으로 매핑한다 - 정책 없는 도메인은 headlessFirst=false 로 보낸다`() {
        val extractor =
            extractorWith { server ->
                server
                    .expect(requestTo("http://extractor.test/internal/extractions/link"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.url").value("https://shop.example.com/p/1"))
                    .andExpect(jsonPath("$.headlessFirst").value(false))
                    .andRespond(
                        withSuccess(
                            """{"name":"나이키","imageUrl":"https://cdn.example.com/i.png","currentPrice":99000,"currency":"KRW"}""",
                            MediaType.APPLICATION_JSON,
                        ),
                    )
            }

        val snapshot = extractor.extract(link)

        assertEquals(link, snapshot.link)
        assertEquals("나이키", snapshot.name)
        assertEquals("https://cdn.example.com/i.png", snapshot.imageUrl)
        assertEquals(99_000, snapshot.currentPrice)
        assertEquals("KRW", snapshot.currency)
    }

    @Test
    fun `라우팅 정책이 HEADLESS_FIRST 인 도메인은 headlessFirst=true 힌트를 실어 보낸다`() {
        // 정책(DB·백오피스)의 단일 진실은 이쪽 — extractor 는 이 힌트로 plain 을 건너뛰고 브라우저 직행한다(계약 §2).
        val extractor =
            extractorWith(route = ExtractionRoute.HEADLESS_FIRST) { server ->
                server
                    .expect(requestTo("http://extractor.test/internal/extractions/link"))
                    .andExpect(jsonPath("$.headlessFirst").value(true))
                    .andRespond(
                        withSuccess(
                            """{"name":"크림 상품","imageUrl":"https://cdn.example.com/k.png","currentPrice":209000,"currency":"KRW"}""",
                            MediaType.APPLICATION_JSON,
                        ),
                    )
            }

        val snapshot = extractor.extract(link)

        assertEquals("크림 상품", snapshot.name)
    }

    @Test
    fun `라우팅 정책이 UNSUPPORTED 여도 힌트는 headlessFirst=false 다 - 직행 힌트는 HEADLESS_FIRST 한정`() {
        // UNSUPPORTED 는 등록 경계(verifyRegistrable)가 막는 정책이라 여기 닿는 건 기존 저장 행의 재파싱 등 —
        // 그 경우에도 브라우저 직행으로 격상하지 않고 기본 체인에 맡긴다.
        val extractor =
            extractorWith(route = ExtractionRoute.UNSUPPORTED) { server ->
                server
                    .expect(requestTo("http://extractor.test/internal/extractions/link"))
                    .andExpect(jsonPath("$.headlessFirst").value(false))
                    .andRespond(
                        withSuccess(
                            """{"name":"나이키","imageUrl":"https://cdn.example.com/i.png","currentPrice":99000,"currency":"KRW"}""",
                            MediaType.APPLICATION_JSON,
                        ),
                    )
            }

        extractor.extract(link)
    }

    @Test
    fun `200 이어도 경계 정규화를 거친다 - non-https imageUrl 은 null 로, 소문자 currency 는 ISO 정규형으로`() {
        // 원격 계약이 정상 값을 보장하더라도 신뢰 경계를 넘어온 값은 embedded 와 같은 fromExtracted 를 다시 거친다(다층 방어).
        // http imageUrl 이 그대로 통과하면 XSS 사다리(클라이언트 <img src>)가 다시 열린다.
        val extractor =
            extractorWith { server ->
                server.expect(requestTo("http://extractor.test/internal/extractions/link")).andRespond(
                    withSuccess(
                        """{"name":"나이키","imageUrl":"http://cdn.evil.com/x.png","currentPrice":99000,"currency":"krw"}""",
                        MediaType.APPLICATION_JSON,
                    ),
                )
            }

        val snapshot = extractor.extract(link)

        assertNull(snapshot.imageUrl, "non-https imageUrl 은 정규화가 null 로 떨궈야 한다")
        assertEquals("KRW", snapshot.currency, "currency 는 ISO 정규형으로 정규화돼야 한다")
    }

    @Test
    fun `200 이어도 범위 위반(음수 가격)은 UNTRUSTWORTHY 확정 실패로 떨어진다 - embedded LLM 경로와 동일`() {
        val extractor =
            extractorWith { server ->
                server.expect(requestTo("http://extractor.test/internal/extractions/link")).andRespond(
                    withSuccess(
                        """{"name":"나이키","imageUrl":"https://cdn.example.com/i.png","currentPrice":-1,"currency":"KRW"}""",
                        MediaType.APPLICATION_JSON,
                    ),
                )
            }

        val e = assertFailsWith<ProductSnapshotException> { extractor.extract(link) }
        assertFalse(AsyncItemParsingWorker.isRetryable(e))
    }

    @Test
    fun `200 응답에 모르는 필드가 있어도 무시하고 매핑한다 (tolerant reader)`() {
        val extractor =
            extractorWith { server ->
                server.expect(requestTo("http://extractor.test/internal/extractions/link")).andRespond(
                    withSuccess(
                        """{"name":"나이키","imageUrl":"https://cdn.example.com/i.png","currentPrice":99000,"currency":null,"futureField":"x"}""",
                        MediaType.APPLICATION_JSON,
                    ),
                )
            }

        val snapshot = extractor.extract(link)

        assertEquals("나이키", snapshot.name)
        assertEquals(null, snapshot.currency)
    }

    @Test
    fun `422 NOT_PRODUCT_PAGE 는 기존 ProductSnapshotException 으로 되돌려 워커 의미(not_product)를 보존한다`() {
        val extractor =
            extractorWith { server ->
                server.expect(requestTo("http://extractor.test/internal/extractions/link")).andRespond(
                    withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""{"code":"NOT_PRODUCT_PAGE"}"""),
                )
            }

        val e = assertFailsWith<ProductSnapshotException> { extractor.extract(link) }
        assertEquals(ErrorCategory.INVALID_INPUT, e.category)
        assertFalse(AsyncItemParsingWorker.isRetryable(e), "확정 실패는 워커가 재시도하면 안 된다")
    }

    @Test
    fun `422 UNTRUSTWORTHY_VALUE 도 기존 ProductSnapshotException 으로 되돌린다`() {
        val extractor =
            extractorWith { server ->
                server.expect(requestTo("http://extractor.test/internal/extractions/link")).andRespond(
                    withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""{"code":"UNTRUSTWORTHY_VALUE"}"""),
                )
            }

        assertFailsWith<ProductSnapshotException> { extractor.extract(link) }
    }

    @Test
    fun `422 의 모르는 code 도 확정 실패다 (tolerant reader) - 비 RETRYABLE 로 워커가 즉시 FAILED`() {
        val extractor =
            extractorWith { server ->
                server.expect(requestTo("http://extractor.test/internal/extractions/link")).andRespond(
                    withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""{"code":"CODE_FROM_THE_FUTURE"}"""),
                )
            }

        val e = assertFailsWith<ProductExtractorException> { extractor.extract(link) }
        assertEquals(ErrorCategory.SERVER_ERROR, e.category)
        assertFalse(AsyncItemParsingWorker.isRetryable(e))
    }

    @Test
    fun `422 body 가 깨져 있어도 status 만으로 확정 실패로 처리한다`() {
        val extractor =
            extractorWith { server ->
                server.expect(requestTo("http://extractor.test/internal/extractions/link")).andRespond(
                    withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("not-json"),
                )
            }

        val e = assertFailsWith<ProductExtractorException> { extractor.extract(link) }
        assertFalse(AsyncItemParsingWorker.isRetryable(e))
    }

    @Test
    fun `422 가 아닌 4xx(404 등)도 일시 실패다 - 422 만 확정, 나머지는 RETRYABLE`() {
        // translate 의 "422 만 확정 실패, 그 외 전부 일시" 의도를 고정한다. 잘못된 base-url 로 인한 404·인증 401 같은
        // 4xx 도 fail-safe 로 재시도 경로를 타야 한다(recover 상한이 바운드). 5xx 와 같은 패턴.
        val extractor =
            extractorWith { server ->
                server.expect(requestTo("http://extractor.test/internal/extractions/link"))
                    .andRespond(withStatus(HttpStatus.NOT_FOUND))
            }

        val e = assertFailsWith<ProductExtractorException> { extractor.extract(link) }
        assertEquals(ErrorCategory.RETRYABLE, e.category)
        assertTrue(AsyncItemParsingWorker.isRetryable(e))
    }

    @Test
    fun `2xx 이어도 필수 필드가 빠진 계약 위반 응답은 일시 실패로 걸러진다`() {
        // extractor 는 자기 쪽에서 필수 필드를 강제하지만, 초기 이관기 버그로 2xx + null 필드가 오면
        // 불완전 스냅샷이 조용히 READY 로 새면 안 된다 — boundary 에서 일시 실패로 걸러 재시도 후 FAILED 로 종결.
        val extractor =
            extractorWith { server ->
                server.expect(requestTo("http://extractor.test/internal/extractions/link")).andRespond(
                    withSuccess(
                        """{"name":"나이키","imageUrl":null,"currentPrice":99000,"currency":"KRW"}""",
                        MediaType.APPLICATION_JSON,
                    ),
                )
            }

        val e = assertFailsWith<ProductExtractorException> { extractor.extract(link) }
        assertEquals(ErrorCategory.RETRYABLE, e.category)
        assertTrue(AsyncItemParsingWorker.isRetryable(e))
    }

    @Test
    fun `5xx 는 일시 실패다 - RETRYABLE 로 워커가 PROCESSING 유지 후 재시도`() {
        val extractor =
            extractorWith { server ->
                server.expect(requestTo("http://extractor.test/internal/extractions/link")).andRespond(withServerError())
            }

        val e = assertFailsWith<ProductExtractorException> { extractor.extract(link) }
        assertEquals(ErrorCategory.RETRYABLE, e.category)
        assertTrue(AsyncItemParsingWorker.isRetryable(e))
    }

    @Test
    fun `연결 실패·타임아웃 같은 transport 장애는 일시 실패다`() {
        val extractor =
            extractorWith { server ->
                server.expect(requestTo("http://extractor.test/internal/extractions/link")).andRespond {
                    throw IOException("connection reset")
                }
            }

        val e = assertFailsWith<ProductExtractorException> { extractor.extract(link) }
        assertEquals(ErrorCategory.RETRYABLE, e.category)
        assertTrue(AsyncItemParsingWorker.isRetryable(e))
    }
}
