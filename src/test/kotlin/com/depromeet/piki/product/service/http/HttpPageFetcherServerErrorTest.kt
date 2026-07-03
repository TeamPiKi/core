package com.depromeet.piki.product.service.http

import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.product.domain.ProductLink
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.web.client.RestClient
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// 대상 서버의 5xx 를 status 별로 일시(RETRYABLE)/영구(SERVER_ERROR)로 가르는지 네트워크 없이 검증한다.
// 봇 차단을 500(no body)으로 응답하는 쇼핑몰이 무의미하게 재시도되지 않도록 500/501 은 영구로 본다.
// 응답은 MockRestServiceServer 로 제어하고, DNS 는 가짜 공인 IP 로 주입해 SSRF 가드를 통과시킨다.
class HttpPageFetcherServerErrorTest {
    private val publicIp: (String) -> Array<InetAddress> = { arrayOf(InetAddress.getByName("93.184.216.34")) }

    private fun fetcherWith(configure: (MockRestServiceServer) -> Unit): HttpPageFetcher {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        configure(server)
        return HttpPageFetcher(builder.build(), RequestScopedDnsResolver(publicIp))
    }

    @Test
    fun `500·501 은 영구 실패로 보아 재시도하지 않는다`() {
        // KREAM 처럼 봇 차단을 body 없는 500 으로 응답하는 케이스. 재시도해도 결정론적으로 재실패한다.
        listOf(HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.NOT_IMPLEMENTED).forEach { status ->
            val fetcher =
                fetcherWith { server ->
                    server.expect(requestTo("https://shop.example.com/p")).andRespond(withStatus(status))
                }

            val ex =
                assertFailsWith<PageFetchException>("$status 는 영구 실패(SERVER_ERROR)여야 함") {
                    fetcher.fetch(ProductLink.parse("https://shop.example.com/p"))
                }

            assertEquals(ErrorCategory.SERVER_ERROR, ex.category)
            assertEquals(HttpStatus.BAD_GATEWAY, ex.httpStatus)
            assertTrue(ex.escalatable, "$status(no-body 봇차단)는 헤드리스로 escalate 대상이어야 함")
        }
    }

    @Test
    fun `body 있는 500·501 은 진짜 서버 장애로 보아 escalate 하지 않는다`() {
        // 봇 차단(no-body 패턴)이 아니라 실제 서버 오류라, 헤드리스로도 못 살려 escalatable=false. status·category 는 500/501 과 동일.
        // 500/501 은 PERMANENT_SERVER_ERRORS 로 같은 body 유무 분기를 타므로 둘 다 순회해 상태코드별 회귀를 막는다.
        listOf(HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.NOT_IMPLEMENTED).forEach { status ->
            val fetcher =
                fetcherWith { server ->
                    server
                        .expect(requestTo("https://shop.example.com/p"))
                        .andRespond(withStatus(status).body("Internal Server Error"))
                }

            val ex =
                assertFailsWith<PageFetchException>("$status body 있는 경우는 escalate 대상이 아님") {
                    fetcher.fetch(ProductLink.parse("https://shop.example.com/p"))
                }

            assertEquals(ErrorCategory.SERVER_ERROR, ex.category)
            assertEquals(HttpStatus.BAD_GATEWAY, ex.httpStatus)
            assertFalse(ex.escalatable, "$status body 있는 경우는 진짜 장애라 escalate 대상이 아님")
        }
    }

    @Test
    fun `502·503·504 는 일시 오류로 보아 재시도 대상이다`() {
        listOf(HttpStatus.BAD_GATEWAY, HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.GATEWAY_TIMEOUT).forEach { status ->
            val fetcher =
                fetcherWith { server ->
                    server.expect(requestTo("https://shop.example.com/p")).andRespond(withStatus(status))
                }

            val ex =
                assertFailsWith<PageFetchException>("$status 는 일시 오류(RETRYABLE)여야 함") {
                    fetcher.fetch(ProductLink.parse("https://shop.example.com/p"))
                }

            assertEquals(ErrorCategory.RETRYABLE, ex.category)
            assertEquals(HttpStatus.BAD_GATEWAY, ex.httpStatus)
            assertFalse(ex.escalatable, "$status(일시 게이트웨이 오류)는 재시도 축이지 escalate 대상이 아님")
        }
    }

    @Test
    fun `4xx 는 입력 오류로 본다`() {
        val fetcher =
            fetcherWith { server ->
                server.expect(requestTo("https://shop.example.com/p")).andRespond(withStatus(HttpStatus.NOT_FOUND))
            }

        val ex =
            assertFailsWith<PageFetchException> {
                fetcher.fetch(ProductLink.parse("https://shop.example.com/p"))
            }

        assertEquals(ErrorCategory.INVALID_INPUT, ex.category)
        assertEquals(HttpStatus.BAD_REQUEST, ex.httpStatus)
        assertFalse(ex.escalatable, "404 는 진짜 없는 페이지라 헤드리스로 escalate 하지 않는다")
    }

    @Test
    fun `403 은 봇 차단 신호로 escalatable 로 표시한다`() {
        // 봇 차단·로그인 벽을 403 으로 응답하는 케이스(쿠팡·올리브영 등). 정적 fetch 로는 막히지만 실제 브라우저면
        // 뚫릴 수 있어, 사용자 매핑(400)은 4xx 와 같게 두되 escalatable 로 표시해 Fallback 이 헤드리스로 넘길 수 있게 한다.
        val fetcher =
            fetcherWith { server ->
                server.expect(requestTo("https://shop.example.com/p")).andRespond(withStatus(HttpStatus.FORBIDDEN))
            }

        val ex =
            assertFailsWith<PageFetchException> {
                fetcher.fetch(ProductLink.parse("https://shop.example.com/p"))
            }

        assertEquals(ErrorCategory.INVALID_INPUT, ex.category)
        assertEquals(HttpStatus.BAD_REQUEST, ex.httpStatus)
        assertTrue(ex.escalatable, "403(봇 차단)은 헤드리스로 escalate 대상이어야 함")
    }
}
