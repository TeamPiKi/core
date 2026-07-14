package com.depromeet.piki.metrics.report

import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubDiscordMessageSender
import com.depromeet.piki.support.uuidToBytes
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import java.sql.Timestamp
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 주간 리포트 수동 엔드포인트 → 실제 MySQL 집계(신규 4쿼리 포함) → embed 조립 → Discord 게시(stub 격리)까지 전 흐름 검증.
// 지난 완결 주 창은 실행 시점 기준 계산되므로 그 창 안에 회원 1명을 심어 카드 반영을 본다. created_at 은 UTC 저장이라 KST→UTC 변환해 심는다.
@Transactional
class WeeklyReportIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var stubSender: StubDiscordMessageSender

    private fun mockMvc() =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    @Test
    fun `수동 엔드포인트가 실제 DB 집계로 카드 3장을 metrics 채널에 게시한다`() {
        stubSender.sent.clear()

        val kst = ZoneId.of("Asia/Seoul")
        val week = ReportWindow.lastCompleteWeek(LocalDateTime.now(kst))
        val withinKst = week.from.plusDays(1).plusHours(12) // 지난 완결 주 안(월 다음날 정오, KST)
        val createdAtUtc = withinKst.atZone(kst).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()
        val userId = UUID.randomUUID()

        jdbcTemplate.update(
            "INSERT INTO users (id, nickname, identity_type, created_at, updated_at) VALUES (?, ?, 'MEMBER', ?, ?)",
            uuidToBytes(userId),
            userId.toString().take(10),
            Timestamp.valueOf(createdAtUtc),
            Timestamp.valueOf(createdAtUtc),
        )
        jdbcTemplate.update(
            "INSERT INTO user_details (user_id, email, provider, social_id, created_at, updated_at) VALUES (?, ?, 'KAKAO', ?, ?, ?)",
            uuidToBytes(userId),
            "w@t.com",
            UUID.randomUUID().toString(),
            Timestamp.valueOf(createdAtUtc),
            Timestamp.valueOf(createdAtUtc),
        )

        mockMvc()
            .perform(post("/admin/metrics/weekly-report").with(csrf()))
            .andExpect(status().isOk)

        assertEquals(1, stubSender.sent.size)
        val sent = stubSender.sent.first()
        assertEquals("test-metrics-channel", sent.channelId)
        assertEquals(3, sent.embeds.size)
        assertEquals("📊 PiKi 주간 리포트", sent.embeds[0]["title"])
        assertEquals("🛍️ 활동", sent.embeds[1]["title"])
        assertEquals("🔧 건강도 · 전달", sent.embeds[2]["title"])

        // 심은 회원 1명이 신규 가입 카드에 반영된다(개발진 제외 집계 · 롤백 tx 라 이 유저만).
        val signupField = fieldValue(sent.embeds[0], "👤 신규 가입")
        assertTrue(signupField.startsWith("1 "), "신규 가입 반영 안 됨: $signupField")
        assertTrue(signupField.contains("회원 1 · 게스트 0"), "회원/게스트 분해 틀림: $signupField")
    }

    @Suppress("UNCHECKED_CAST")
    private fun fieldValue(
        embed: Map<String, Any>,
        name: String,
    ): String = (embed["fields"] as List<Map<String, Any>>).first { it["name"] == name }["value"] as String
}
