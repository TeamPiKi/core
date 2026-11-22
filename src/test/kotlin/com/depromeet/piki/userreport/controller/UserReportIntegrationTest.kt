package com.depromeet.piki.userreport.controller

import com.depromeet.piki.admin.config.AdminProperties
import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.common.exception.CommonErrorCode
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubDiscordWebhookSender
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.UserErrorCode
import com.depromeet.piki.userreport.controller.dto.ReportRequest
import com.depromeet.piki.userreport.domain.ReportReason
import com.depromeet.piki.userreport.domain.UserReport
import com.depromeet.piki.userreport.domain.UserReportErrorCode
import com.depromeet.piki.userreport.service.DiscordReportNotifier
import com.depromeet.piki.userreport.service.dto.ReportNotification
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 사용자 신고(#1291). 핵심은 "신고가 차단을 동반한다" 와 "알림 실패가 신고를 실패시키지 않는다" 이다.
@Transactional
class UserReportIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var webApplicationContext: WebApplicationContext

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired private lateinit var jwtProvider: JwtProvider

    @Autowired private lateinit var stubSender: StubDiscordWebhookSender

    @Test
    fun `신고하면 접수되고 그 상대가 차단된다`() {
        val mockMvc = mockMvc()
        val reporter = member()
        val reported = member()

        mockMvc
            .perform(post("/api/v1/reports/users/$reported").by(reporter).json(spam()))
            .andExpect(status().isCreated)

        assertEquals(1, reportCountOf(reported), "신고가 저장돼야 한다")
        // 신고한 사람은 상대를 더 보고 싶지 않다는 뜻이라, 차단을 따로 누르게 하지 않는다
        mockMvc
            .perform(get("/api/v1/blocks").by(reporter))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.blocked[0].userId").value(reported.toString()))
    }

    @Test
    fun `신고는 친구 관계도 끊는다`() {
        val mockMvc = mockMvc()
        val reporter = member()
        val reported = member()
        mockMvc.perform(post("/api/v1/friends/$reported").by(reporter)).andExpect(status().isOk)

        mockMvc
            .perform(post("/api/v1/reports/users/$reported").by(reporter).json(spam()))
            .andExpect(status().isCreated)

        // 차단이 친구를 끊으므로(4단계) 신고도 같은 결과가 된다
        mockMvc
            .perform(get("/api/v1/friends").by(reporter))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.friendCount").value(0))
    }

    @Test
    fun `같은 상대를 여러 번 신고할 수 있다`() {
        val mockMvc = mockMvc()
        val reporter = member()
        val reported = member()

        repeat(2) {
            mockMvc
                .perform(post("/api/v1/reports/users/$reported").by(reporter).json(spam()))
                .andExpect(status().isCreated)
        }

        // 막지 않는다 — 반복 신고 자체가 운영 판단의 신호다
        assertEquals(2, reportCountOf(reported))
    }

    @Test
    fun `기타 사유는 상세 내용을 받고 다른 사유는 거부한다`() {
        val mockMvc = mockMvc()
        val reporter = member()
        val reported = member()

        mockMvc
            .perform(
                post("/api/v1/reports/users/$reported")
                    .by(reporter)
                    .json(ReportRequest(ReportReason.OTHER, "개인 계좌로 결제를 유도했어요.")),
            ).andExpect(status().isCreated)
        mockMvc
            .perform(
                post("/api/v1/reports/users/$reported")
                    .by(reporter)
                    .json(ReportRequest(ReportReason.SPAM_OR_AD, "여기엔 못 쓴다")),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value(UserReportErrorCode.DETAIL_NOT_ALLOWED.code))
    }

    @Test
    fun `상세 내용이 300자를 넘으면 400 이다`() {
        val mockMvc = mockMvc()
        val reporter = member()
        val reported = member()

        mockMvc
            .perform(
                post("/api/v1/reports/users/$reported")
                    .by(reporter)
                    .json(ReportRequest(ReportReason.OTHER, "가".repeat(UserReport.MAX_DETAIL_LENGTH + 1))),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value(CommonErrorCode.INVALID_INPUT.code))
            .andExpect(jsonPath("$.detail").value(ReportRequest.DETAIL_SIZE_MESSAGE))
    }

    @Test
    fun `자기 자신은 신고할 수 없다`() {
        val mockMvc = mockMvc()
        val me = member()

        mockMvc
            .perform(post("/api/v1/reports/users/$me").by(me).json(spam()))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value(UserReportErrorCode.SELF_NOT_ALLOWED.code))
    }

    @Test
    fun `게스트는 신고할 수 없다`() {
        val mockMvc = mockMvc()
        val guest = UUID.randomUUID().also { insertUser(it, IdentityType.GUEST) }
        val reported = member()

        mockMvc
            .perform(post("/api/v1/reports/users/$reported").by(guest, IdentityType.GUEST).json(spam()))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value(UserReportErrorCode.GUEST_CANNOT_USE.code))
    }

    @Test
    fun `탈퇴한 상대를 신고하면 409 가 아니라 404 다`() {
        val mockMvc = mockMvc()
        val reporter = member()
        val withdrawn = member()
        jdbcTemplate.update("UPDATE users SET deleted_at = NOW(6) WHERE id = ?", uuidToBytes(withdrawn))

        mockMvc
            .perform(post("/api/v1/reports/users/$withdrawn").by(reporter).json(spam()))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(UserErrorCode.NOT_FOUND.code))
    }

    // 알림은 부수 기능이다. 게시가 실패해도 신고는 이미 저장됐으므로 요청은 성공해야 한다
    @Test
    fun `알림 게시가 실패해도 신고는 접수된다`() {
        val mockMvc = mockMvc()
        val reporter = member()
        val reported = member()
        stubSender.result = false // 공유 stub — 이 테스트의 실패 시나리오를 명시 세팅

        mockMvc
            .perform(post("/api/v1/reports/users/$reported").by(reporter).json(spam()))
            .andExpect(status().isCreated)

        assertEquals(1, reportCountOf(reported))
    }

    // 채널에 있는 사람 모두가 보는 메시지라 닉네임을 담지 않는다. 사용자 특정은 id 까지만
    @Test
    fun `알림 payload 는 두 사용자 id 와 사유 누적 수 시각을 담고 닉네임은 담지 않는다`() {
        val reportedId = UUID.randomUUID()
        val reporterId = UUID.randomUUID()
        stubSender.result = true // 공유 stub — 게시 성공 시나리오를 명시 세팅
        val notifier = notifier(webhookUrl = "https://discord.test/webhook", environment = "prod")

        notifier.notify(
            ReportNotification(
                reporterId = reporterId,
                reportedId = reportedId,
                reason = ReportReason.IMPERSONATION,
                reportCountOfReported = 3,
                detail = null,
                // 저장값은 UTC 다. KST 로 9시간 더해 찍혀야 한다
                reportedAt = LocalDateTime.of(2026, 1, 2, 3, 4, 5),
            ),
        )

        val sent = stubSender.sent.last()
        assertEquals("https://discord.test/webhook", sent.webhookUrl, "주간 리포트 채널과 분리돼야 한다")
        // 보내는 이름이 환경을 드러내야 한다 — 두 환경이 같은 이름이면 어느 쪽 신고인지 구분이 안 된다
        assertEquals("prod-신고알림", sent.username)
        val rendered = objectMapper.writeValueAsString(sent.embeds)
        assertTrue(rendered.contains("누적 3건"), "누적 신고 수가 실려야 한다: $rendered")
        assertTrue(rendered.contains(ReportReason.IMPERSONATION.name), "사유가 실려야 한다: $rendered")
        assertTrue(rendered.contains(reportedId.toString()), "신고된 사용자 id 가 실려야 한다")
        assertTrue(rendered.contains(reporterId.toString()), "신고한 사용자 id 가 실려야 한다")
        assertTrue(rendered.contains("2026-01-02 12:04:05"), "시각이 KST 로 실려야 한다: $rendered")
    }

    // 웹훅을 안 정했어도 신고는 돌아야 한다
    @Test
    fun `웹훅이 설정되지 않으면 게시하지 않고 조용히 넘어간다`() {
        val before = stubSender.sent.size
        val notifier = notifier(webhookUrl = "", environment = "prod")

        notifier.notify(notificationOf())

        assertEquals(before, stubSender.sent.size, "웹훅이 없으면 아무것도 보내지 않는다")
    }

    // 로컬은 ADMIN_ENVIRONMENT 가 비어 있다. 이름이 빈 문자열이면 디스코드가 게시를 거부한다
    @Test
    fun `환경명이 비면 보내는 이름을 local 로 둔다`() {
        val notifier = notifier(webhookUrl = "https://discord.test/webhook", environment = "")

        notifier.notify(notificationOf())

        assertEquals("local-신고알림", stubSender.sent.last().username)
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────────────

    // 보내는 이름은 환경에서 파생되므로, 테스트가 웹훅 주소와 환경명을 직접 조립한다
    private fun notifier(
        webhookUrl: String,
        environment: String,
    ) = DiscordReportNotifier(
        stubSender,
        AdminProperties(discordReportWebhookUrl = webhookUrl, environment = environment),
    )

    private fun notificationOf() =
        ReportNotification(
            reporterId = UUID.randomUUID(),
            reportedId = UUID.randomUUID(),
            reason = ReportReason.SPAM_OR_AD,
            reportCountOfReported = 1,
            detail = null,
            reportedAt = LocalDateTime.of(2026, 1, 2, 3, 4, 5),
        )

    private fun mockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    private fun spam() = ReportRequest(ReportReason.SPAM_OR_AD)

    private fun reportCountOf(reportedId: UUID): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM user_reports WHERE reported_id = ?",
            Int::class.java,
            uuidToBytes(reportedId),
        ) ?: 0

    private fun insertUser(
        userId: UUID,
        identityType: IdentityType,
    ) {
        jdbcTemplate.update(
            "INSERT INTO users (id, nickname, identity_type, created_at, updated_at) VALUES (?, ?, ?, NOW(6), NOW(6))",
            uuidToBytes(userId),
            userId.toString().take(10),
            identityType.name,
        )
    }

    private fun member(): UUID = UUID.randomUUID().also { insertUser(it, IdentityType.MEMBER) }

    private fun MockHttpServletRequestBuilder.by(
        userId: UUID,
        identityType: IdentityType = IdentityType.MEMBER,
    ): MockHttpServletRequestBuilder =
        header(HttpHeaders.AUTHORIZATION, "Bearer ${jwtProvider.generateAccessToken(userId, identityType)}")

    private fun MockHttpServletRequestBuilder.json(body: Any): MockHttpServletRequestBuilder =
        contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body))
}
