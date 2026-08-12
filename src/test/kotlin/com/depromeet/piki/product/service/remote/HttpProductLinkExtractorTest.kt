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

    // headlessAllowed 의 기본을 false 로 둔다 — 허가는 정책 행에 명시적으로 켜야만 생기는 사실이라(default-deny),
    // Fake 도 그 전제를 지켜야 "허가를 안 켰는데 true 로 나가는" 회귀를 잡을 수 있다.
    private class FakeRoutingPolicy(
        private val route: ExtractionRoute? = null,
        private val headlessAllowed: Boolean = false,
    ) : ExtractionRoutingPolicy {
        override fun routeOf(link: ProductLink): ExtractionRoute? = route

        override fun headlessAllowedOf(link: ProductLink): Boolean = headlessAllowed
    }

    // 지정한 축에만 값을 준다 — target 을 무시하고 늘 같은 값을 돌려주면, 링크 추출기가 IMAGE 축을 읽는
    // 회귀가 그대로 통과한다. 축 분리가 이 기능의 전제라 Fake 가 그 전제를 지켜야 한다.
    private class FakeModelSettings(
        private val axis: ExtractionTarget,
        private val model: String?,
    ) : ExtractionModelSettings {
        override fun modelOf(target: ExtractionTarget): String? = model?.takeIf { target == axis }
    }

    private fun extractorWith(
        route: ExtractionRoute? = null,
        headlessAllowed: Boolean = false,
        model: String? = null,
        server: (MockRestServiceServer) -> Unit,
    ): HttpProductLinkExtractor {
        val builder = RestClient.builder().baseUrl("http://extractor.test")
        val mockServer = MockRestServiceServer.bindTo(builder).build()
        server(mockServer)
        return HttpProductLinkExtractor(
            builder.build(),
            FakeRoutingPolicy(route, headlessAllowed),
            FakeModelSettings(ExtractionTarget.LINK, model),
        )
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
        assertEquals(99_000, snapshot.price)
        assertEquals("KRW", snapshot.currency)
    }

    @Test
    fun `라우팅 정책이 HEADLESS_FIRST 이고 허가된 도메인은 headlessFirst=true 힌트를 실어 보낸다`() {
        // 정책(DB·백오피스)의 단일 진실은 이쪽 — extractor 는 이 힌트로 plain 을 건너뛰고 브라우저 직행한다(계약 §2).
        // headlessFirst 는 route 가 HEADLESS_FIRST 이면서 허가된 도메인에만 켜지므로 허가(headlessAllowed=true)를 함께 준다.
        val extractor =
            extractorWith(route = ExtractionRoute.HEADLESS_FIRST, headlessAllowed = true) { server ->
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
    fun `정책 행이 없는 도메인은 headlessAllowed=false 로 보낸다 - 허가 원장의 기본은 거부다`() {
        // 대부분의 도메인이 이 경로다. 여기가 true 로 새면 "허가받은 곳만 브라우저로 연다"는 약속이 통째로 무너진다.
        val extractor =
            extractorWith { server ->
                server
                    .expect(requestTo("http://extractor.test/internal/extractions/link"))
                    .andExpect(jsonPath("$.headlessAllowed").value(false))
                    .andRespond(
                        withSuccess(
                            """{"name":"나이키","imageUrl":"https://cdn.example.com/i.png","currentPrice":99000,"currency":"KRW"}""",
                            MediaType.APPLICATION_JSON,
                        ),
                    )
            }

        assertEquals("나이키", extractor.extract(link).name)
    }

    @Test
    fun `허가받은 도메인은 headlessAllowed=true 를 실어 보낸다`() {
        // 허가 원장(extraction_platform_policies.headless_allowed)의 단일 진실은 이쪽 DB 이고, 무상태인 extractor 는
        // 요청 단위로만 받는다 — 이 필드가 빠지면 저쪽은 허가 여부를 알 길이 없다.
        val extractor =
            extractorWith(route = ExtractionRoute.HEADLESS_FIRST, headlessAllowed = true) { server ->
                server
                    .expect(requestTo("http://extractor.test/internal/extractions/link"))
                    .andExpect(jsonPath("$.headlessAllowed").value(true))
                    .andExpect(jsonPath("$.headlessFirst").value(true))
                    .andRespond(
                        withSuccess(
                            """{"name":"허가 상품","imageUrl":"https://cdn.example.com/k.png","currentPrice":209000,"currency":"KRW"}""",
                            MediaType.APPLICATION_JSON,
                        ),
                    )
            }

        assertEquals("허가 상품", extractor.extract(link).name)
    }

    @Test
    fun `route 가 HEADLESS_FIRST 여도 허가가 없으면 headlessFirst=false 로 보낸다 - 모순 요청을 막는 게이트`() {
        // 허가 없이 HEADLESS_FIRST 로 남은 행(이 기능 이전 데이터·구버전이 만든 행)을 만나도 core 가 스스로
        // default-deny 를 지킨다 — "브라우저 직행(headlessFirst)"과 "허가 없음(headlessAllowed=false)"이 함께
        // 나가는 모순 요청을 원천 차단한다. extractor 가 먼저 배포되는 구간의 안전이 이 게이트에 달려 있다.
        val extractor =
            extractorWith(route = ExtractionRoute.HEADLESS_FIRST, headlessAllowed = false) { server ->
                server
                    .expect(requestTo("http://extractor.test/internal/extractions/link"))
                    .andExpect(jsonPath("$.headlessFirst").value(false))
                    .andExpect(jsonPath("$.headlessAllowed").value(false))
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
    fun `허가받지 않은 도메인은 정책이 있어도 headlessAllowed=false 다`() {
        // 정책 행이 있다는 사실만으로 허가가 되지 않는다. 허가 컬럼을 켜기 전(또는 이 기능 이전에 만들어진 행)에는
        // 정책이 무엇이든 브라우저를 열 수 없다.
        val extractor =
            extractorWith(route = ExtractionRoute.SUPPORTED) { server ->
                server
                    .expect(requestTo("http://extractor.test/internal/extractions/link"))
                    .andExpect(jsonPath("$.headlessAllowed").value(false))
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
        // 원격 계약이 정상 값을 보장하더라도 신뢰 경계를 넘어온 값은 fromExtracted 정규화를 다시 거친다(다층 방어).
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
    fun `200 이어도 범위 위반(음수 가격)은 UNTRUSTWORTHY 확정 실패로 떨어진다`() {
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
        // extractor 는 자기 쪽에서 필수 필드를 강제하지만, 버그로 2xx + null 필드가 오면
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

    // 모델 지정(백오피스·DB)이 LINK 축에서 요청 힌트로 실린다. 지정이 없으면 싣지 않아 extractor 기본 모델로
    // 동작하는데, 그건 위 테스트들이 이미 지나는 경로다(요청에 model 을 기대하지 않는다).
    @Test
    fun `LINK 축에 지정된 모델을 요청 힌트로 싣는다`() {
        val extractor =
            extractorWith(model = "gemini-3-flash") { server ->
                server
                    .expect(requestTo("http://extractor.test/internal/extractions/link"))
                    .andExpect(jsonPath("$.model").value("gemini-3-flash"))
                    .andRespond(
                        withSuccess(
                            """{"name":"나이키","imageUrl":"https://cdn.example.com/i.png","currentPrice":99000,"currency":"KRW"}""",
                            MediaType.APPLICATION_JSON,
                        ),
                    )
            }

        assertEquals("나이키", extractor.extract(link).name)
    }
}
