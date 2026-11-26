package com.depromeet.piki.admin.report

import com.depromeet.piki.admin.access.AdminAllowlistService
import com.depromeet.piki.admin.access.DiscordInteraction
import com.depromeet.piki.admin.access.GrantDest
import com.depromeet.piki.admin.access.ReportGrantComponentHandler
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
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// 신고 알림을 받은 운영자의 진입 경로(#1291). 버튼 클릭이 원타임 링크를 주고, 그 링크가 그 사람의 신고 상세로
// 떨어져야 한다 — 홈이나 목록으로 떨어지면 알림에서 바로 들어가는 흐름이 끊긴다.
//
// 목적지가 토큰에 서명돼 있다는 것도 함께 고정한다. 임의 경로를 실을 수 있게 되면 서명된 오픈 리다이렉트가 된다.
@Transactional
class ReportGrantEntryIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var webApplicationContext: WebApplicationContext

    @Autowired private lateinit var allowlistService: AdminAllowlistService

    @Autowired private lateinit var adminProperties: AdminProperties

    @Autowired private lateinit var componentHandler: ReportGrantComponentHandler

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Test
    fun `신고 목적지 grant 링크는 그 사람의 신고 상세로 떨어지고 세션을 발급한다`() {
        // Redis 키(allowlist·세션)는 롤백 대상이 아니라 남으므로 다른 테스트와 겹치지 않는 IP 를 쓴다
        val ip = "203.0.113.41"
        val reportedId = UUID.randomUUID()
        val token =
            allowlistService.issueGrantToken(
                userId = "discord-user-1291",
                name = "신고담당운영자",
                env = adminProperties.environment,
                dest = GrantDest.REPORTS,
                target = reportedId,
            )

        val response =
            mockMvc()
                .perform(get("/admin-access/grant").param("token", token).with(realIp(ip)))
                .andExpect(status().is3xxRedirection)
                .andExpect(redirectedUrl("/admin/reports/$reportedId"))
                .andReturn()
                .response

        // 백오피스 화면이라 세션(신원)이 함께 발급돼야 한다 — 없으면 게이트가 다음 요청을 막는다
        response.getCookie("SESSION")?.value ?: error("세션 쿠키가 발급되지 않았다 — 신고 화면은 백오피스라 세션이 필요하다")
    }

    // 대상 없이 발급한 링크는 목록으로. 알림 밖(슬래시커맨드 dest:reports)에서 들어오는 경로다
    @Test
    fun `대상이 없는 신고 목적지 링크는 목록으로 떨어진다`() {
        val token =
            allowlistService.issueGrantToken(
                userId = "discord-user-1291-list",
                name = "신고담당운영자",
                env = adminProperties.environment,
                dest = GrantDest.REPORTS,
            )

        mockMvc()
            .perform(get("/admin-access/grant").param("token", token).with(realIp("203.0.113.42")))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/admin/reports"))
    }

    // 테스트 컨텍스트의 admin.environment 는 비어 있으므로, 버튼이 싣는 환경은 grantHosts 에 있는 값을 쓴다
    @Test
    fun `버튼을 누르면 본인에게만 보이는 링크를 받는다`() {
        val reportedId = UUID.randomUUID()

        val response = componentHandler.handle(interaction(), "dev:$reportedId")

        val data = response["data"] as Map<*, *>
        // 링크가 채널에 새면 관리자 확인이 무의미해진다
        assertEquals(64, data["flags"], "응답은 ephemeral(본인만 보임)이어야 한다")
        val rendered = objectMapper.writeValueAsString(data)
        assertContains(
            rendered,
            "https://dev.test.local/admin-access/grant?token=",
            message = "그 환경의 원타임 grant 링크가 실려야 한다",
        )
        // 토큰만 싣는다 — 대상 id 를 URL 에 직접 실으면 링크를 주운 사람이 목적지를 바꿔 볼 수 있다
        assertFalse(rendered.contains("/admin/reports/$reportedId"), "목적지 경로를 URL 로 노출하지 않는다")
    }

    @Test
    fun `버튼 인자가 깨졌으면 링크를 주지 않는다`() {
        val broken =
            listOf(
                "", // 인자 없음
                "prod", // 대상 없음
                "prod:not-a-uuid", // UUID 아님
                "unknown-env:${UUID.randomUUID()}", // 모르는 환경
            )

        broken.forEach { argument ->
            val rendered = objectMapper.writeValueAsString(componentHandler.handle(interaction(), argument))
            assertFalse(rendered.contains("grant?token="), "깨진 인자($argument)에 링크를 주면 안 된다")
            assertTrue(rendered.contains("처리 불가"), "거부 사유를 보여줘야 한다: $argument")
        }
    }

    private fun interaction(): DiscordInteraction =
        DiscordInteraction(
            root = objectMapper.readTree("""{"type":3,"data":{"custom_id":"report-grant"}}"""),
            userId = "discord-user-1291",
            userName = "신고담당운영자",
            clientIp = "203.0.113.43",
        )

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
