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
    fun `다른 사설대역(10·192_168)·link-local 프록시 뒤 요청도 X-Real-IP 를 클라 IP 로 쓴다`() {
        // 신뢰 프록시는 특정 CIDR 하드코딩이 아니라 RFC1918 전 대역 + link-local + loopback 이다
        // (docker gateway IP 변동에 안 깨지게). 172.17 외 사설·link-local 경계도 잠근다.
        assertEquals("203.0.113.7", ClientIp.of(request("10.0.0.1", "203.0.113.7")))
        assertEquals("203.0.113.7", ClientIp.of(request("192.168.1.1", "203.0.113.7")))
        assertEquals("203.0.113.7", ClientIp.of(request("169.254.0.1", "203.0.113.7")))
    }

    @Test
    fun `공인 IP 는 X-Real-IP 가 사설이어도 신뢰하지 않는다`() {
        // 신뢰 판정은 remoteAddr(실 연결 IP) 기준이다 — 공인 IP 직접 접근이면 헤더 내용과 무관하게 remoteAddr 채택.
        assertEquals("1.1.1.1", ClientIp.of(request("1.1.1.1", "10.0.0.5")))
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
