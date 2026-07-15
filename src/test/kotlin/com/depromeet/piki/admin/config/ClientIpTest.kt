package com.depromeet.piki.admin.config

import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import kotlin.test.assertEquals

// ClientIp 신뢰 프록시 판정 단위 테스트.
// 회귀 가드(2026-07-14): docker userland-proxy 가 source 를 docker0 gateway(172.17.0.1)로 SNAT 해
// 모든 요청 IP 가 gateway 하나로 뭉개지던 버그(allowlist 격리 무력화)를 막는다.
class ClientIpTest {
    private fun request(
        remote: String,
        realIp: String? = null,
    ) = MockHttpServletRequest().apply {
        remoteAddr = remote
        realIp?.let { addHeader("X-Real-IP", it) }
    }

    @Test
    fun `docker gateway(사설대역) 뒤 요청은 nginx 가 세팅한 X-Real-IP 를 클라 IP 로 쓴다`() {
        // userland-proxy SNAT 로 remoteAddr 이 172.17.0.1 이어도 실제 클라 IP 를 살려야 격리가 작동한다.
        assertEquals("203.0.113.7", ClientIp.of(request("172.17.0.1", "203.0.113.7")))
    }

    @Test
    fun `loopback 뒤 요청도 X-Real-IP 를 클라 IP 로 쓴다`() {
        assertEquals("203.0.113.7", ClientIp.of(request("127.0.0.1", "203.0.113.7")))
    }

    @Test
    fun `공인 IP 에서 직접 온 요청은 X-Real-IP 를 신뢰하지 않고 remoteAddr 를 쓴다`() {
        // 신뢰 프록시(사설/loopback)가 아니면 헤더가 위조됐을 수 있어 remoteAddr 를 채택한다.
        assertEquals("8.8.8.8", ClientIp.of(request("8.8.8.8", "203.0.113.7")))
    }

    @Test
    fun `X-Real-IP 가 없으면 remoteAddr 로 폴백한다`() {
        assertEquals("172.17.0.1", ClientIp.of(request("172.17.0.1")))
    }

    @Test
    fun `X-Real-IP 가 공백이면 remoteAddr 로 폴백한다`() {
        assertEquals("172.17.0.1", ClientIp.of(request("172.17.0.1", "   ")))
    }
}
