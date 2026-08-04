package com.depromeet.piki.product.service.remote

import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
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

// 저장 게이트의 판정이 원격 응답 3갈래(200 / 422+code / 그 외)와 정확히 맞물리는지 고정한다. 여기서 나온
// 메시지가 그대로 백오피스 화면의 거절 사유가 되므로, 사유별 문구까지 상수로 단언한다.
// 외부 경계(원격 HTTP)는 MockRestServiceServer 로 격리한다(HttpProductLinkExtractorTest 와 같은 방식).
class HttpExtractionModelProbeTest {
    private val probeUrl = "http://extractor.test${HttpExtractionModelProbe.PROBE_PATH}"

    private fun probeWith(server: (MockRestServiceServer) -> Unit): HttpExtractionModelProbe {
        val builder = RestClient.builder().baseUrl("http://extractor.test")
        val mockServer = MockRestServiceServer.bindTo(builder).build()
        server(mockServer)
        return HttpExtractionModelProbe(builder.build())
    }

    private fun rejectionMessage(body: String): String {
        val probe =
            probeWith { server ->
                server
                    .expect(requestTo(probeUrl))
                    .andRespond(
                        withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(body),
                    )
            }
        val rejection = assertFailsWith<IllegalArgumentException> { probe.verify(ExtractionTarget.LINK, "gemini-x") }
        return rejection.message.orEmpty()
    }

    @Test
    fun `200 이면 통과하고 요청엔 모델과 경로가 실린다`() {
        val probe =
            probeWith { server ->
                server
                    .expect(requestTo(probeUrl))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(jsonPath("$.model").value("gemini-3-flash"))
                    .andExpect(jsonPath("$.target").value("IMAGE"))
                    .andRespond(withSuccess())
            }

        probe.verify(ExtractionTarget.IMAGE, "gemini-3-flash")
    }

    @Test
    fun `422 MODEL_NOT_FOUND 는 모델명을 확인하라는 사유로 거절한다`() {
        assertEquals(
            HttpExtractionModelProbe.MESSAGE_NOT_FOUND,
            rejectionMessage("""{"code":"${HttpExtractionModelProbe.CODE_MODEL_NOT_FOUND}"}"""),
        )
    }

    @Test
    fun `422 MODEL_INCOMPATIBLE 은 다른 모델을 고르라는 사유로 거절한다`() {
        assertEquals(
            HttpExtractionModelProbe.MESSAGE_INCOMPATIBLE,
            rejectionMessage("""{"code":"${HttpExtractionModelProbe.CODE_MODEL_INCOMPATIBLE}"}"""),
        )
    }

    // 이 바이너리보다 새 extractor 가 사유를 늘렸을 때 — 거절이라는 사실은 지키고 사유만 일반 문구로 떨어진다.
    // 새 code 마다 매핑을 늘리지 않는 것은 추출 계약(RemoteExtractionContract)과 같은 판단이다.
    @Test
    fun `422 의 모르는 code 는 일반 거절 사유가 된다`() {
        assertEquals(HttpExtractionModelProbe.MESSAGE_REJECTED, rejectionMessage("""{"code":"SOMETHING_NEW"}"""))
    }

    // 422 는 "이 모델은 안 된다"는 확정 판정이라 저장을 막아야 하지만, 그 외 status 는 모델의 문제가 아니다
    // (잘못된 base-url 404 · 인증 401 · extractor 다운 5xx). 재시도를 안내해 오판으로 모델을 지우게 두지 않는다.
    @Test
    fun `422 가 아닌 status 는 일시 실패로 안내한다`() {
        val probe = probeWith { server -> server.expect(requestTo(probeUrl)).andRespond(withServerError()) }

        val e = assertFailsWith<IllegalArgumentException> { probe.verify(ExtractionTarget.LINK, "gemini-x") }
        assertEquals(HttpExtractionModelProbe.MESSAGE_UNAVAILABLE, e.message)
    }

    @Test
    fun `연결 실패·타임아웃 같은 transport 장애도 일시 실패로 안내한다`() {
        val probe =
            probeWith { server ->
                server.expect(requestTo(probeUrl)).andRespond { throw IOException("connection reset") }
            }

        val e = assertFailsWith<IllegalArgumentException> { probe.verify(ExtractionTarget.LINK, "gemini-x") }
        assertEquals(HttpExtractionModelProbe.MESSAGE_UNAVAILABLE, e.message)
    }
}
