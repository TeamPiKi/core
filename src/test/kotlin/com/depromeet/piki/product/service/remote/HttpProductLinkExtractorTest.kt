package com.depromeet.piki.product.service.remote

import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.item.service.AsyncItemParsingWorker
import com.depromeet.piki.item.service.ItemParsingMetrics
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.domain.ProductLinkException
import com.depromeet.piki.product.routing.DomainAccess
import com.depromeet.piki.product.routing.DomainAccessPolicy
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

    // 기본을 "정책 행 없음"으로 둔다 — 허락은 명시적으로 켜야만 생기는 사실이라(default-deny), Fake 도 그
    // 전제를 지켜야 "허락을 안 켰는데 true 로 나가는" 회귀를 잡을 수 있다.
    private class FakeAccessPolicy(
        private val access: DomainAccess? = null,
    ) : DomainAccessPolicy {
        override fun accessOf(link: ProductLink): DomainAccess? = access

        override fun authorizedFor(link: ProductLink): Boolean = access == DomainAccess.ALLOWED
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
        access: DomainAccess? = null,
        model: String? = null,
        server: (MockRestServiceServer) -> Unit,
    ): HttpProductLinkExtractor {
        val builder = RestClient.builder().baseUrl("http://extractor.test")
        val mockServer = MockRestServiceServer.bindTo(builder).build()
        server(mockServer)
        return HttpProductLinkExtractor(
            builder.build(),
            FakeAccessPolicy(access),
            FakeModelSettings(ExtractionTarget.LINK, model),
        )
    }

    @Test
    fun `200 응답의 추출 결과를 ProductSnapshot 으로 매핑한다 - 정책 없는 도메인은 authorized=false 로 보낸다`() {
        val extractor =
            extractorWith { server ->
                server
                    .expect(requestTo("http://extractor.test/internal/extractions/link"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.url").value("https://shop.example.com/p/1"))
                    .andExpect(jsonPath("$.authorized").value(false))
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
    fun `허락받은 도메인은 authorized=true 를 실어 보낸다`() {
        // 허락 판정의 원장은 이쪽(DB·백오피스) — extractor·renderer 는 이 값만큼 수단을 열 뿐 스스로 알지 않는다.
        val extractor =
            extractorWith(access = DomainAccess.ALLOWED) { server ->
                server
                    .expect(requestTo("http://extractor.test/internal/extractions/link"))
                    .andExpect(jsonPath("$.authorized").value(true))
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
    fun `정책 행이 없으면 authorized=false 로 나간다`() {
        // 대부분의 도메인이 이 경우다. 허락은 명시적으로 켜야만 생기는 사실이라 기본은 거부다.
        val extractor =
            extractorWith() { server ->
                server
                    .expect(requestTo("http://extractor.test/internal/extractions/link"))
                    .andExpect(jsonPath("$.authorized").value(false))
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
    fun `허락받은 도메인은 authorized=true 를 실어 보낸다 - 원장은 이쪽에 있다`() {
        // 허가 원장(domain_access_policies.access)의 단일 진실은 이쪽 DB 이고, 무상태인 extractor 는
        // 요청 단위로만 받는다 — 이 필드가 빠지면 저쪽은 허가 여부를 알 길이 없다.
        val extractor =
            extractorWith(access = DomainAccess.ALLOWED) { server ->
                server
                    .expect(requestTo("http://extractor.test/internal/extractions/link"))
                    .andExpect(jsonPath("$.authorized").value(true))
                    .andExpect(jsonPath("$.authorized").value(true))
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
    fun `차단 도메인은 요청 자체를 보내지 않는다`() {
        // 등록 경계가 새 등록을 막지만 그것만으로는 이미 담긴 아이템의 재파싱이 그대로 나간다 — extractor 로 나가는
        // 유일한 출구인 여기서 막아야 "거부 의사를 확인한 곳에 다시 두드리지 않는다"가 성립한다.
        // MockRestServiceServer 에 아무 기대도 걸지 않았으므로, 요청이 나가면 그 자체로 실패한다.
        val extractor = extractorWith(access = DomainAccess.BLOCKED) { }

        assertFailsWith<ProductLinkException> { extractor.extract(link) }
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
        assertEquals(ItemParsingMetrics.REASON_NOT_PRODUCT, ItemParsingMetrics.reasonOf(e))
        assertFalse(AsyncItemParsingWorker.isRetryable(e), "확정 실패는 워커가 재시도하면 안 된다")
    }

    @Test
    fun `422 확정 실패 code 는 전이 판정은 그대로 둔 채 bucket 별 reason 으로만 갈린다`() {
        // 원격 code 를 우리 예외로 번역하는 분기 망라(#936). code 마다 다른 건 **reason 뿐**이고, "422 = 확정 실패
        // (비 RETRYABLE)" 라는 전이 판정은 전부 같다 — 그 두 축이 섞이지 않았음을 한 테스트에서 함께 고정한다.
        // 카탈로그 bucket 과 이 reason 이 같은지는 ExtractionErrorCatalogTest 가 별도로 대조한다.
        val expected =
            mapOf(
                "NOT_PRODUCT_PAGE" to ItemParsingMetrics.REASON_NOT_PRODUCT,
                "INVALID_URL" to ItemParsingMetrics.REASON_NOT_PRODUCT,
                "EMPTY_SHELL" to ItemParsingMetrics.REASON_UNREADABLE,
                "NO_EXTRACTABLE_CONTENT" to ItemParsingMetrics.REASON_UNREADABLE,
                "FETCH_CLIENT_ERROR" to ItemParsingMetrics.REASON_BLOCKED,
                "PERMANENT_UPSTREAM" to ItemParsingMetrics.REASON_BLOCKED,
                "UNTRUSTWORTHY_VALUE" to ItemParsingMetrics.REASON_EXTRACT_QUALITY,
                "LLM_INVALID_RESPONSE" to ItemParsingMetrics.REASON_EXTRACT_QUALITY,
                "IMAGE_UNSUPPORTED" to ItemParsingMetrics.REASON_EXTRACT_QUALITY,
                "BLOCKED_HOST" to ItemParsingMetrics.REASON_INTERNAL_ERROR,
                "TOO_MANY_REDIRECTS" to ItemParsingMetrics.REASON_INTERNAL_ERROR,
                "MALFORMED_REDIRECT" to ItemParsingMetrics.REASON_INTERNAL_ERROR,
            )

        expected.forEach { (code, reason) ->
            val extractor =
                extractorWith { server ->
                    server.expect(requestTo("http://extractor.test/internal/extractions/link")).andRespond(
                        withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("""{"code":"$code"}"""),
                    )
                }

            val e = assertFailsWith<BaseException> { extractor.extract(link) }
            assertEquals(reason, ItemParsingMetrics.reasonOf(e), "$code 의 메트릭 reason")
            assertFalse(AsyncItemParsingWorker.isRetryable(e), "$code 는 422 라 재시도 대상이 아니어야 한다")
        }
    }

    @Test
    fun `422 의 모르는 code 도 확정 실패다 (tolerant reader) - internal_error 로 세어 매핑 누락이 드러난다`() {
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
        // 이름을 모르는 실패를 다른 바구니에 섞지 않는다 — 조사 대상(internal_error)으로 센다.
        assertEquals(ItemParsingMetrics.REASON_INTERNAL_ERROR, ItemParsingMetrics.reasonOf(e))
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
        assertEquals(ItemParsingMetrics.REASON_INTERNAL_ERROR, ItemParsingMetrics.reasonOf(e))
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
    fun `2xx 부분값은 막지 않고 통과시킨다 - 채운 값을 보존해 도메인이 INCOMPLETE 로 판정하게 둔다`() {
        // extractor 는 값이 하나라도 있으면 200 으로 내려보낸다(extractor#37). 경계가 세 필드를 다 요구하면
        // 그 200 이 계약 위반으로 튕겨 재시도 후 FAILED 가 되고, INCOMPLETE 로 가는 길이 닫힌다(#950 의 prod 사고).
        // 여기서 통과시켜야 markExtracted 가 "일부만 얻음 → INCOMPLETE" 를 판정할 수 있다.
        val extractor =
            extractorWith { server ->
                server.expect(requestTo("http://extractor.test/internal/extractions/link")).andRespond(
                    withSuccess(
                        """{"name":"핸드 워시","imageUrl":"https://cdn.example.com/i.png","currentPrice":null,"currency":"KRW"}""",
                        MediaType.APPLICATION_JSON,
                    ),
                )
            }

        val snapshot = extractor.extract(link)

        assertEquals("핸드 워시", snapshot.name)
        assertEquals("https://cdn.example.com/i.png", snapshot.imageUrl)
        assertNull(snapshot.price, "못 건진 필드는 null 로 남아 사용자가 채운다")
    }

    @Test
    fun `2xx 인데 값이 하나도 없으면 계약 위반이라 일시 실패로 걸러진다`() {
        // 하나도 못 건진 경우는 extractor 가 422(UNTRUSTWORTHY_VALUE)로 닫는 계약이라, 그게 200 으로 오면
        // 저쪽 버그다 — 빈 스냅샷이 조용히 흘러 들어가지 않게 경계에서 일시 실패로 걸러 재시도한다.
        val extractor =
            extractorWith { server ->
                server.expect(requestTo("http://extractor.test/internal/extractions/link")).andRespond(
                    withSuccess(
                        """{"name":null,"imageUrl":null,"currentPrice":null,"currency":"KRW"}""",
                        MediaType.APPLICATION_JSON,
                    ),
                )
            }

        val e = assertFailsWith<ProductExtractorException> { extractor.extract(link) }
        assertEquals(ErrorCategory.RETRYABLE, e.category)
        assertTrue(AsyncItemParsingWorker.isRetryable(e))
    }

    @Test
    fun `blank name 만 담긴 2xx 도 값이 없는 것으로 본다 - 경계 정규화가 어차피 떨군다`() {
        // currency 도 단독으로는 "건졌다"의 근거가 되지 못한다(READY 필수가 아니다). 이 판정 기준은
        // 도메인(ItemSnapshot.hasNoExtractedValue)과 같아야 한다 — 어긋나면 경계를 통과한 응답이
        // 곧바로 FAILED 로 떨어져 재시도 예산만 태운다.
        val extractor =
            extractorWith { server ->
                server.expect(requestTo("http://extractor.test/internal/extractions/link")).andRespond(
                    withSuccess(
                        """{"name":"   ","imageUrl":null,"currentPrice":null,"currency":"KRW"}""",
                        MediaType.APPLICATION_JSON,
                    ),
                )
            }

        val e = assertFailsWith<ProductExtractorException> { extractor.extract(link) }
        assertEquals(ErrorCategory.RETRYABLE, e.category)
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
