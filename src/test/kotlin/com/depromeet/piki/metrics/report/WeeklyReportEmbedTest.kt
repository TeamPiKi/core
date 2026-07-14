package com.depromeet.piki.metrics.report

import com.depromeet.piki.metrics.dashboard.MetricsSnapshot
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeeklyReportEmbedTest {
    private fun sampleReport(
        signupNewTrend: Trend = Trend(12, false),
        participants: Long = 140,
        avgAttempts: Double? = 1.2,
    ) = WeeklyReport(
        weekLabel = "07/06(월) ~ 07/12(일)",
        month30Label = "06/13 ~ 07/12",
        signupNew = 42,
        signupNewTrend = signupNewTrend,
        members = 30,
        guests = 12,
        conversions = 8,
        withdrawals = 5,
        d1Rate = 34,
        dauSeries =
            listOf(
                MetricsSnapshot.DateCount(LocalDate.of(2026, 7, 6), 42),
                MetricsSnapshot.DateCount(LocalDate.of(2026, 7, 9), 88),
                MetricsSnapshot.DateCount(LocalDate.of(2026, 7, 12), 48),
            ),
        wau = 210,
        weeklyProvider = mapOf("KAKAO" to 20, "GOOGLE" to 8, "APPLE" to 2),
        cumulativeUsers = 1234,
        cumulativeProviderPct = mapOf("KAKAO" to 62, "GOOGLE" to 30, "APPLE" to 8),
        wishTotal = 156,
        wishTotalTrend = Trend(5, false),
        wishUrl = 120,
        wishImage = 36,
        parseSuccessRate = 94,
        tournamentCreated = 22,
        tournamentCreatedTrend = Trend(8, false),
        participants = participants,
        completionRate = 71,
        plays = 980,
        parseFailRate = 6,
        avgAttempts = avgAttempts,
        pushSent = 3,
        deliverySuccessRate = 97,
        ctrApproxPct = 12,
        d30SignupNew = 180,
        d30WishTotal = 640,
        d30TournamentCreated = 88,
    )

    @Test
    fun `카드 3장을 만든다`() {
        val embeds = WeeklyReportEmbed.build(sampleReport())

        assertEquals(3, embeds.size)
    }

    @Test
    fun `첫 카드 제목과 집계 기간을 담는다`() {
        val embeds = WeeklyReportEmbed.build(sampleReport())

        val card1 = embeds[0]
        assertEquals("📊 PiKi 주간 리포트", card1["title"])
        assertEquals("07/06(월) ~ 07/12(일) · KST", card1["description"])
    }

    @Test
    fun `양의 WoW 는 상승 화살표로 표시한다`() {
        val embeds = WeeklyReportEmbed.build(sampleReport(signupNewTrend = Trend(12, false)))

        val signupField = fieldValue(embeds[0], "👤 신규 가입")
        assertTrue(signupField.startsWith("42 ▲12%"), "실제: $signupField")
    }

    @Test
    fun `전주가 0이면 신규 배지를 붙인다`() {
        val embeds = WeeklyReportEmbed.build(sampleReport(signupNewTrend = Trend(null, true)))

        val signupField = fieldValue(embeds[0], "👤 신규 가입")
        assertTrue(signupField.contains("신규"), "실제: $signupField")
    }

    @Test
    fun `DAU peak 은 값과 날짜 요일을 함께 표시한다`() {
        val embeds = WeeklyReportEmbed.build(sampleReport())

        assertEquals("88 (07/09 목)", fieldValue(embeds[0], "☀️ DAU peak"))
    }

    @Test
    fun `일별 DAU 추이를 요일별로 나열한다`() {
        val embeds = WeeklyReportEmbed.build(sampleReport())

        val trend = fieldValue(embeds[0], "📊 일별 DAU")
        assertTrue(trend.contains("월42") && trend.contains("목88") && trend.contains("일48"), "실제: $trend")
    }

    @Test
    fun `누적 provider 는 footer 에 퍼센트로 표시한다`() {
        val embeds = WeeklyReportEmbed.build(sampleReport())

        val footer = footerText(embeds[0])
        assertTrue(footer.contains("카카오 62%") && footer.contains("누적 1,234"), "실제: $footer")
    }

    @Test
    fun `참가자가 0이면 완료율 계산이 0으로 안전하다`() {
        val report = sampleReport(participants = 0).copy(completionRate = 0)

        val embeds = WeeklyReportEmbed.build(report)

        assertEquals("0%", fieldValue(embeds[1], "완료율"))
    }

    @Test
    fun `파싱 평균 시도가 없으면 대시로 표시한다`() {
        val embeds = WeeklyReportEmbed.build(sampleReport(avgAttempts = null))

        val field = fieldValue(embeds[2], "파싱 실패율")
        assertTrue(field.contains("—"), "실제: $field")
    }

    @Suppress("UNCHECKED_CAST")
    private fun fields(embed: Map<String, Any>): List<Map<String, Any>> = embed["fields"] as List<Map<String, Any>>

    private fun fieldValue(
        embed: Map<String, Any>,
        name: String,
    ): String = fields(embed).first { it["name"] == name }["value"] as String

    @Suppress("UNCHECKED_CAST")
    private fun footerText(embed: Map<String, Any>): String = (embed["footer"] as Map<String, Any>)["text"] as String
}
