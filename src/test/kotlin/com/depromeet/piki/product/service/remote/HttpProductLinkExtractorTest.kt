package com.depromeet.piki.product.service.remote

import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.item.service.AsyncItemParsingWorker
import com.depromeet.piki.product.domain.ProductLink
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
import kotlin.test.assertTrue

// 원격 추출 응답(계약 3갈래: 2xx / 422+code / 그 외)이 워커의 재시도 판정(category)과 정확히 맞물리게
// 번역되는지 검증한다. 외부 경계(원격 HTTP)는 MockRestServiceServer 로 격리한다(HttpPageFetcher 테스트와 같은 방식).
class HttpProductLinkExtractorTest {
    private val link = ProductLink.parse("https://shop.example.com/p/1")

    private fun extractorWith(server: (MockRestServiceServer) -> Unit): HttpProductLinkExtractor {
        val builder = RestClient.builder().baseUrl("http://extractor.test")
        val mockServer = MockRestServiceServer.bindTo(builder).build()
        server(mockServer)
        return HttpProductLinkExtractor(builder.build())
    }

    @Test
    fun `200 응답의 추출 결과를 ProductSnapshot 으로 매핑한다`() {
        val extractor =
            extractorWith { server ->
                server
                    .expect(requestTo("http://extractor.test/internal/extractions/link"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.url").value("https://shop.example.com/p/1"))
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

        val e = assertFailsWith<RemoteExtractionException> { extractor.extract(link) }
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

        val e = assertFailsWith<RemoteExtractionException> { extractor.extract(link) }
        assertFalse(AsyncItemParsingWorker.isRetryable(e))
    }

    @Test
    fun `5xx 는 일시 실패다 - RETRYABLE 로 워커가 PROCESSING 유지 후 재시도`() {
        val extractor =
            extractorWith { server ->
                server.expect(requestTo("http://extractor.test/internal/extractions/link")).andRespond(withServerError())
            }

        val e = assertFailsWith<RemoteExtractionException> { extractor.extract(link) }
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

        val e = assertFailsWith<RemoteExtractionException> { extractor.extract(link) }
        assertEquals(ErrorCategory.RETRYABLE, e.category)
        assertTrue(AsyncItemParsingWorker.isRetryable(e))
    }
}
