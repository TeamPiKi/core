package com.depromeet.piki.admin.config

import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun `X-Real-IP 없이 docker gateway 에서 온 요청은 박스 내부 직결이다`() {
        // 관측 수집기(Alloy)의 scrape 가 이 모양이다 — nginx 를 안 거쳐 헤더가 없고, SNAT 로 출발지는 gateway.
        // loopback 여부로 판정하면 여기서 막혀 앱 메트릭이 통째로 실명한다(#872).
        assertTrue(ClientIp.isInBoxDirect(request("172.17.0.1")))
    }

    @Test
    fun `X-Real-IP 없는 loopback·다른 사설대역도 박스 내부 직결이다`() {
        assertTrue(ClientIp.isInBoxDirect(request("127.0.0.1")))
        assertTrue(ClientIp.isInBoxDirect(request("10.0.0.1")))
    }

    @Test
    fun `X-Real-IP 가 있으면 nginx 경유(외부 요청)라 내부 직결이 아니다`() {
        // 헤더 유무가 내부·외부를 가르는 축이다 — 이게 뒤집히면 게이트가 외부까지 통과시킨다.
        assertFalse(ClientIp.isInBoxDirect(request("172.17.0.1", "203.0.113.7")))
        assertFalse(ClientIp.isInBoxDirect(request("127.0.0.1", "203.0.113.7")))
    }

    @Test
    fun `X-Real-IP 가 공백이어도 헤더가 있으면 내부 직결이 아니다`() {
        // fail-closed. of() 는 공백을 "쓸 수 없는 값" 으로 보고 remoteAddr 로 폴백하지만, 여기 질문은
        // "앞단이 손댔는가" 라 존재 자체가 신호다. nginx 는 빈 X-Real-IP 를 만들지 않으므로 공백 헤더는
        // 우리 배포 경로 밖에서 실린 것이고, 게이트는 그 모호함을 차단으로 해석한다.
        assertFalse(ClientIp.isInBoxDirect(request("172.17.0.1", "   ")))
    }

    @Test
    fun `공인 IP 에서 직접 온 요청은 헤더가 없어도 내부 직결이 아니다`() {
        assertFalse(ClientIp.isInBoxDirect(request("8.8.8.8")))
    }
}
