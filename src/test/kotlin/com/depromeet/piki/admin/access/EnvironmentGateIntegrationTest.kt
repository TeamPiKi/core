package com.depromeet.piki.admin.access

import com.depromeet.piki.admin.config.AdminProperties
import com.depromeet.piki.support.IntegrationTestSupport
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

// dev 환경 게이트(EnvironmentAccessFilter)의 허용·차단을 실제 요청으로 통과시켜 검증한다.
//
// 경로 매칭(EnvironmentAccessFilterTest)과 IP 판정(ClientIpTest)만으로는 이 정책이 실제 요청에서 어떻게
// 끝나는지 안 잡힌다 — #872 의 원인이 정확히 그 사각이었다. 그때 ClientIp 는 멀쩡했고 필터가 그와 어긋나는
// 판정(loopback 만 통과)을 따로 들고 있었는데, 두 단위 테스트 다 초록불이었고 배포도 성공했으며 dev 앱
// 메트릭만 몇 주간 조용히 실명했다. 그래서 "필터를 실제로 태우는" 이 층이 필요하다.
//
// 게이트는 배포가 dev 에만 주는 플래그(ENV_ACCESS_GATE)라 공유 컨텍스트에선 꺼져 있다. 클래스별 프로퍼티
// 분기는 컨텍스트 캐시 규약이 금지하므로, 게이트만 켠 설정으로 필터를 만들어 MockMvc 에 끼운다 — 협력자
// (allowlist·Redis)는 컨텍스트의 실제 빈 그대로라 stub 이 없다.
class EnvironmentGateIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var allowlistService: AdminAllowlistService

    @Autowired
    private lateinit var adminProperties: AdminProperties

    @Test
    fun `박스 내부 직결(관측 수집기)은 게이트를 통과해 actuator 를 받는다`() {
        // Alloy scrape 의 모양 — nginx 를 안 거쳐 X-Real-IP 가 없고, docker SNAT 로 출발지는 gateway.
        // 이 케이스가 막히면 앱 메트릭이 통째로 사라진다(#872 의 그 증상).
        gatedMockMvc()
            .perform(get("/actuator/prometheus").with(from(remote = "172.17.0.1")))
            .andExpect(status().isOk)
    }

    @Test
    fun `nginx 를 거친 외부 요청은 allowlist 에 없으면 404 로 막힌다`() {
        // 실제 외부 접근의 모양 — nginx 가 X-Real-IP 에 진짜 클라 IP 를 덮어써 넘긴다.
        gatedMockMvc()
            .perform(get("/actuator/prometheus").with(from(remote = "172.17.0.1", realIp = "203.0.113.10")))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `grant 받은 IP 는 게이트를 통과한다`() {
        // Discord 로 grant 한 IP 가 문서를 볼 수 있어야 한다 — 게이트의 존재 이유 자체.
        // TTL sliding 이라 Redis 에 남으므로 다른 테스트와 겹치지 않는 IP 를 쓴다.
        val granted = "203.0.113.11"
        allowlistService.grant(granted, "integration-test")

        gatedMockMvc()
            .perform(get("/docs/index.html").with(from(remote = "172.17.0.1", realIp = granted)))
            .andExpect(status().isOk)
    }

    @Test
    fun `공백 X-Real-IP 는 내부 직결로 접히지 않고 막힌다`() {
        // fail-closed. 헤더가 값 없이 실려 오면 우리 배포 경로(nginx)가 만든 것이 아니므로 통과시키지 않는다.
        gatedMockMvc()
            .perform(get("/actuator/prometheus").with(from(remote = "172.17.0.1", realIp = "   ")))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `공인 IP 직접 접근은 헤더가 없어도 막힌다`() {
        // nginx 를 우회해 앱 포트에 직접 닿은 경우 — 헤더 부재만으로 통과시키면 게이트가 무의미해진다.
        gatedMockMvc()
            .perform(get("/actuator/prometheus").with(from(remote = "8.8.8.8")))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `게이트 밖 경로는 외부 요청이어도 그대로 서빙된다`() {
        // 백엔드 API·health 는 게이트 대상이 아니다 — 게이트가 dev 백엔드를 통째로 막는 회귀 가드.
        gatedMockMvc()
            .perform(get("/health").with(from(remote = "172.17.0.1", realIp = "203.0.113.12")))
            .andExpect(status().isOk)
    }

    // 게이트만 켠 설정으로 실제 필터를 만들어 끼운다. allowlist 는 컨텍스트의 실제 빈이라 Redis 를 그대로 탄다.
    private fun gatedMockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .addFilters<DefaultMockMvcBuilder>(
                EnvironmentAccessFilter(allowlistService, adminProperties.copy(environmentGate = true)),
            ).build()

    // MockMvc 기본 remoteAddr 은 127.0.0.1 이라 그대로 두면 모든 요청이 내부 직결로 통과한다 — 출발지를 명시한다.
    private fun from(
        remote: String,
        realIp: String? = null,
    ) = { request: org.springframework.mock.web.MockHttpServletRequest ->
        request.remoteAddr = remote
        realIp?.let { request.addHeader("X-Real-IP", it) }
        request
    }
}
