package com.depromeet.piki.admin.report

import com.depromeet.piki.admin.access.AdminAllowlistService
import com.depromeet.piki.admin.access.GrantDest
import com.depromeet.piki.admin.config.AdminProperties
import com.depromeet.piki.support.IntegrationTestSupport
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext

// 신고 알림을 받은 운영자의 진입 경로(#1291). `/piki-admin env:<env> dest:reports` 가 발급한 링크가
// 신고 목록으로 떨어져야 한다 — 홈으로 떨어지면 알림에서 바로 들어가는 흐름이 끊긴다.
//
// 목적지가 토큰에 서명돼 있다는 것도 함께 고정한다. 임의 경로를 실을 수 있게 되면 서명된 오픈 리다이렉트가 된다.
@Transactional
class ReportGrantEntryIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var webApplicationContext: WebApplicationContext

    @Autowired private lateinit var allowlistService: AdminAllowlistService

    @Autowired private lateinit var adminProperties: AdminProperties

    @Test
    fun `신고 목적지 grant 링크는 신고 목록으로 떨어지고 세션을 발급한다`() {
        // Redis 키(allowlist·세션)는 롤백 대상이 아니라 남으므로 다른 테스트와 겹치지 않는 IP 를 쓴다
        val ip = "203.0.113.41"
        val token =
            allowlistService.issueGrantToken(
                userId = "discord-user-1291",
                name = "신고담당운영자",
                env = adminProperties.environment,
                dest = GrantDest.REPORTS,
            )

        val response =
            mockMvc()
                .perform(get("/admin-access/grant").param("token", token).with(realIp(ip)))
                .andExpect(status().is3xxRedirection)
                .andExpect(redirectedUrl("/admin/reports"))
                .andReturn()
                .response

        // 백오피스 화면이라 세션(신원)이 함께 발급돼야 한다 — 없으면 게이트가 다음 요청을 막는다
        assertSessionIssued(response.getCookie("SESSION")?.value)
    }

    private fun assertSessionIssued(sessionCookie: String?) {
        sessionCookie ?: error("세션 쿠키가 발급되지 않았다 — 신고 화면은 백오피스라 세션이 필요하다")
    }

    private fun mockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .addFilters<DefaultMockMvcBuilder>(
                webApplicationContext.getBean("springSessionRepositoryFilter", jakarta.servlet.Filter::class.java),
            ).build()

    private fun realIp(ip: String) =
        { request: MockHttpServletRequest ->
            request.addHeader("X-Real-IP", ip)
            request
        }
}
