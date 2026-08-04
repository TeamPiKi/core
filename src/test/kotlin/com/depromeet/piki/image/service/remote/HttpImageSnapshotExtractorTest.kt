package com.depromeet.piki.image.service.remote

import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.storage.S3Properties
import com.depromeet.piki.item.service.AsyncImageParsingWorker
import com.depromeet.piki.product.service.remote.ExtractionModelSettings
import com.depromeet.piki.product.service.remote.ExtractionTarget
import com.depromeet.piki.product.service.remote.ProductExtractorException
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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 이미지 고유의 것만 고정한다 — 요청 모양(bucket·key·경로), link=null 스냅샷, 이미지 전용 422 code(IMAGE_UNSUPPORTED)
// 취급, 그리고 확정/일시 갈래가 이미지 워커의 재시도 판정(AsyncImageParsingWorker.isRetryable)과 맞물리는 정합.
// 3갈래 번역·경계 정규화·계약 위반 가드의 분기 망라는 같은 공유 함수(RemoteExtractionContract.postForSnapshot)를
// 쓰는 HttpProductLinkExtractorTest 가 이미 고정하므로 여기서 반복하지 않는다.
// 외부 경계(원격 HTTP)는 MockRestServiceServer 로 격리한다.
class HttpImageSnapshotExtractorTest {
    private val imageKey = "items/raw/0f8a1c2e.png"

    private class FakeModelSettings(
        private val model: String? = null,
    ) : ExtractionModelSettings {
        override fun modelOf(target: ExtractionTarget): String? = model
    }

    private fun extractorWith(
        model: String? = null,
        server: (MockRestServiceServer) -> Unit,
    ): HttpImageSnapshotExtractor {
        val builder = RestClient.builder().baseUrl("http://extractor.test")
        val mockServer = MockRestServiceServer.bindTo(builder).build()
        server(mockServer)
        return HttpImageSnapshotExtractor(
            builder.build(),
            S3Properties(bucket = "test-piki-images", publicBaseUrl = "https://img.test"),
            FakeModelSettings(model),
        )
    }

    @Test
    fun `200 응답을 ProductSnapshot 으로 매핑한다 - 요청엔 S3Properties 의 bucket 과 raw key 가 실리고 link 는 null`() {
        val extractor =
            extractorWith { server ->
                server
                    .expect(requestTo("http://extractor.test/internal/extractions/image"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.bucket").value("test-piki-images"))
                    .andExpect(jsonPath("$.key").value(imageKey))
                    .andRespond(
                        withSuccess(
                            """{"name":"나이키 에어포스","imageUrl":"https://img.test/items/out.png","currentPrice":129000,"currency":"KRW"}""",
                            MediaType.APPLICATION_JSON,
                        ),
                    )
            }

        val snapshot = extractor.extract(imageKey)

        assertNull(snapshot.link, "이미지 추출엔 원본 URL 이 없다 (계약 §2 image)")
        assertEquals("나이키 에어포스", snapshot.name)
        assertEquals("https://img.test/items/out.png", snapshot.imageUrl)
        assertEquals(129_000, snapshot.price)
        assertEquals("KRW", snapshot.currency)
    }

    @Test
    fun `이미지 전용 422 code(IMAGE_UNSUPPORTED)도 별도 매핑 없이 확정 실패다 - 워커가 즉시 FAILED`() {
        // 미지원 이미지 형식은 다시 보내도 결과가 같다. code 는 관측용이라 새 code 마다 매핑을 늘리지 않는다(tolerant reader).
        val extractor =
            extractorWith { server ->
                server.expect(requestTo("http://extractor.test/internal/extractions/image")).andRespond(
                    withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""{"code":"IMAGE_UNSUPPORTED"}"""),
                )
            }

        val e = assertFailsWith<ProductExtractorException> { extractor.extract(imageKey) }
        assertEquals(ErrorCategory.SERVER_ERROR, e.category)
        assertFalse(AsyncImageParsingWorker.isRetryable(e), "확정 실패는 워커가 재시도하면 안 된다")
    }

    @Test
    fun `5xx(원격 STORAGE_ERROR 등)는 일시 실패다 - 워커가 PROCESSING 유지 후 recover 재시도`() {
        val extractor =
            extractorWith { server ->
                server.expect(requestTo("http://extractor.test/internal/extractions/image")).andRespond(withServerError())
            }

        val e = assertFailsWith<ProductExtractorException> { extractor.extract(imageKey) }
        assertEquals(ErrorCategory.RETRYABLE, e.category)
        assertTrue(AsyncImageParsingWorker.isRetryable(e))
    }

    // 축 분리의 실증 — 이미지 경로는 IMAGE 지정만 따른다. FakeModelSettings 가 축과 무관하게 같은 값을 주므로
    // 이 테스트가 고정하는 것은 "요청에 실린다"까지이고, 어느 축을 읽는지는 소비 지점(modelOf 인자)이 진다.
    @Test
    fun `IMAGE 축에 지정된 모델을 요청 힌트로 싣는다`() {
        val extractor =
            extractorWith(model = "gemini-3-pro-vision") { server ->
                server
                    .expect(requestTo("http://extractor.test/internal/extractions/image"))
                    .andExpect(jsonPath("$.model").value("gemini-3-pro-vision"))
                    .andRespond(
                        withSuccess(
                            """{"name":"나이키","imageUrl":"https://img.test/items/x.png","currentPrice":99000,"currency":"KRW"}""",
                            MediaType.APPLICATION_JSON,
                        ),
                    )
            }

        assertEquals("나이키", extractor.extract(imageKey).name)
    }
}
