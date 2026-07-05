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
    fun `500·501 은 body 유무와 무관하게 escalatable 이다`() {
        // 봇 방어가 500 에 body(캡차·차단 페이지)를 실을 수 있어 body 로 장애/차단을 가르지 않는다. 대형 몰은 상시 가용이라
        // 우리가 받는 500/501 은 대개 봇 방어다. body 있는 케이스도 escalatable=true 임을 고정해 body 의존 회귀를 막는다.
        listOf(HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.NOT_IMPLEMENTED).forEach { status ->
            val fetcher =
                fetcherWith { server ->
                    server
                        .expect(requestTo("https://shop.example.com/p"))
                        .andRespond(withStatus(status).body("차단 안내 페이지 HTML"))
                }

            val ex =
                assertFailsWith<PageFetchException>("$status body 있어도 escalatable") {
                    fetcher.fetch(ProductLink.parse("https://shop.example.com/p"))
                }

            assertEquals(ErrorCategory.SERVER_ERROR, ex.category)
            assertEquals(HttpStatus.BAD_GATEWAY, ex.httpStatus)
            assertTrue(ex.escalatable, "$status body 있어도 escalate 대상이어야 함(봇 방어가 body 를 실을 수 있음)")
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
    fun `4xx 는 입력 오류(400)로 노출하되 봇 클로킹 가능성으로 escalatable 이다`() {
        // 봇 방어가 404("없는 척")·403(차단)·429(throttle)로 클로킹할 수 있어, 4xx 는 헤드리스로 뚫릴 후보로 본다(무조건 폴백).
        // 사용자 매핑은 입력 오류라 400, escalatable=true.
        listOf(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND, HttpStatus.GONE, HttpStatus.TOO_MANY_REQUESTS).forEach { status ->
            val fetcher =
                fetcherWith { server ->
                    server.expect(requestTo("https://shop.example.com/p")).andRespond(withStatus(status))
                }

            val ex =
                assertFailsWith<PageFetchException>("$status 는 escalatable 이어야 함") {
                    fetcher.fetch(ProductLink.parse("https://shop.example.com/p"))
                }

            assertEquals(ErrorCategory.INVALID_INPUT, ex.category)
            assertEquals(HttpStatus.BAD_REQUEST, ex.httpStatus)
            assertTrue(ex.escalatable, "$status 는 헤드리스로 escalate 대상이어야 함")
        }
    }
}
