package com.depromeet.piki.userreport.controller

import com.depromeet.piki.admin.config.AdminProperties
import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.common.exception.CommonErrorCode
import com.depromeet.piki.metrics.report.DiscordMessageSender
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubDiscordMessageSender
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
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.ObjectProvider
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

    @Autowired private lateinit var stubSender: StubDiscordMessageSender

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
    fun `알림 payload 는 사유와 누적 수를 담고 닉네임은 담지 않는다`() {
        val reportedId = UUID.randomUUID()
        val reporterId = UUID.randomUUID()
        stubSender.result = true // 공유 stub — 게시 성공 시나리오를 명시 세팅
        val notifier = notifier(channelId = "report-channel")

        notifier.notify(
            ReportNotification(
                reportedId = reportedId,
                reportCountOfReported = 3,
                reportedJoinedAt = LocalDateTime.of(2026, 1, 2, 3, 4),
                reportedIsMember = true,
                reportedFolderCount = 2,
                reportedWishCount = 9,
                reporterId = reporterId,
                reason = ReportReason.IMPERSONATION,
                detail = null,
            ),
        )

        val sent = stubSender.sent.last()
        assertEquals("report-channel", sent.channelId, "주간 리포트 채널과 분리돼야 한다")
        val rendered = objectMapper.writeValueAsString(sent.embeds)
        assertTrue(rendered.contains("누적 3건"), "누적 신고 수가 실려야 한다: $rendered")
        assertTrue(rendered.contains(ReportReason.IMPERSONATION.name), "사유가 실려야 한다: $rendered")
        assertTrue(rendered.contains(reportedId.toString()), "신고된 사용자 id 가 실려야 한다")
        assertTrue(rendered.contains(reporterId.toString()), "신고한 사용자 id 가 실려야 한다")
    }

    // 채널을 안 정했어도 신고는 돌아야 한다 — dev 는 채널을 비워 두는 운영 방식이다
    @Test
    fun `채널이 설정되지 않으면 게시하지 않고 조용히 넘어간다`() {
        val before = stubSender.sent.size
        val notifier = notifier(channelId = "")

        notifier.notify(
            ReportNotification(
                reportedId = UUID.randomUUID(),
                reportCountOfReported = 1,
                reportedJoinedAt = LocalDateTime.now(),
                reportedIsMember = true,
                reportedFolderCount = 0,
                reportedWishCount = 0,
                reporterId = UUID.randomUUID(),
                reason = ReportReason.SPAM_OR_AD,
                detail = null,
            ),
        )

        assertEquals(before, stubSender.sent.size, "채널이 없으면 아무것도 보내지 않는다")
    }

    // admin 이 꺼진 환경엔 전송기 빈이 아예 없다. 그때 컨텍스트가 뜨고 알림만 건너뛰어야 한다
    @Test
    fun `전송기가 없으면 예외 없이 건너뛴다`() {
        val notifier = notifier(channelId = "report-channel", sender = null)

        notifier.notify(
            ReportNotification(
                reportedId = UUID.randomUUID(),
                reportCountOfReported = 1,
                reportedJoinedAt = LocalDateTime.of(2026, 1, 2, 3, 4),
                reportedIsMember = false,
                reportedFolderCount = 0,
                reportedWishCount = 0,
                reporterId = UUID.randomUUID(),
                reason = ReportReason.OTHER,
                detail = "상세",
            ),
        )
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────────────

    // 알림기는 전송기를 ObjectProvider 로 받는다(admin 꺼짐 대응). 테스트가 채널·전송기 유무를 직접 조립한다
    private fun notifier(
        channelId: String,
        sender: DiscordMessageSender? = stubSender,
    ) = DiscordReportNotifier(providerOf(sender), AdminProperties(discordReportChannelId = channelId))

    private fun providerOf(sender: DiscordMessageSender?): ObjectProvider<DiscordMessageSender> =
        object : ObjectProvider<DiscordMessageSender> {
            override fun getObject(): DiscordMessageSender =
                sender ?: throw NoSuchBeanDefinitionException(DiscordMessageSender::class.java)

            override fun getObject(vararg args: Any?): DiscordMessageSender = getObject()

            override fun getIfAvailable(): DiscordMessageSender? = sender

            override fun getIfUnique(): DiscordMessageSender? = sender
        }

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
