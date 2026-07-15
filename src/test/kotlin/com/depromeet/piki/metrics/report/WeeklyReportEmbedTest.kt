package com.depromeet.piki.metrics.report

import com.depromeet.piki.metrics.dashboard.MetricsSnapshot
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeeklyReportEmbedTest {
    private fun sampleReport(
        signupNewTrend: Trend = Trend(12, false),
        participants: Long = 140,
        avgAttempts: Double? = 1.2,
        deliverySuccessRate: Int? = 97,
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
        deliverySuccessRate = deliverySuccessRate,
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
    fun `음의 WoW 는 하락 화살표로 표시한다`() {
        val embeds = WeeklyReportEmbed.build(sampleReport(signupNewTrend = Trend(-5, false)))

        val signupField = fieldValue(embeds[0], "👤 신규 가입")
        assertTrue(signupField.startsWith("42 ▼5%"), "실제: $signupField")
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

    // completionRate 를 손으로 박지 않고, 참가자 0 스냅샷을 WeeklyReport.of() 에 넣어 실제 계산 경로
    // (pct(completed, participants) 의 0 분모 처리)가 예외 없이 0 을 내는지 검증한다.
    @Test
    fun `참가자가 0인 스냅샷으로도 완주율이 0으로 안전하게 계산돼 렌더된다`() {
        val report =
            WeeklyReport.of(
                weekLabel = "07/06(월) ~ 07/12(일)",
                month30Label = "06/13 ~ 07/12",
                cur = zeroSnapshot(),
                prev = zeroSnapshot(),
                d30 = zeroSnapshot(),
                cumulativeProvider = emptyMap(),
                wau = 0,
                withdrawals = 0,
                avgAttempts = null,
            )

        assertEquals(0, report.completionRate) // 0 분모에도 예외 없이 0

        val embeds = WeeklyReportEmbed.build(report)
        assertTrue(fieldValue(embeds[1], "참가자 완주율").startsWith("0%"), "0 분모는 0% 로 렌더돼야 한다")
    }

    // 모든 지표가 0 인 스냅샷 — 참가자 0(분모 0) 계산 경로 검증용.
    private fun zeroSnapshot() =
        MetricsSnapshot(
            from = LocalDateTime.of(2026, 7, 6, 0, 0),
            to = LocalDateTime.of(2026, 7, 13, 0, 0),
            signup = MetricsSnapshot.Signup(0, 0, 0, 0, emptyMap(), 0),
            wish = MetricsSnapshot.Wish(0, 0, 0, 0, 0),
            tournament = MetricsSnapshot.Tournament(0, 0, 0, 0, 0),
            pushReachableUsers = 0,
            retention = MetricsSnapshot.Retention(0, 0, emptyList()),
            push = MetricsSnapshot.Push(emptyMap(), 0, 0, 0, 0, 0),
            hourlySignups = emptyList(),
        )

    @Test
    fun `파싱 평균 시도가 없으면 대시로 표시한다`() {
        val embeds = WeeklyReportEmbed.build(sampleReport(avgAttempts = null))

        val field = fieldValue(embeds[2], "파싱 실패율")
        assertTrue(field.contains("—"), "실제: $field")
    }

    @Test
    fun `공지 발송이 없으면 전달 성공률을 0퍼센트가 아니라 금주 공지 없음으로 표시한다`() {
        val embeds = WeeklyReportEmbed.build(sampleReport(deliverySuccessRate = null))

        assertEquals("금주 공지 없음", fieldValue(embeds[2], "공지 전달 성공률"))
    }

    @Test
    fun `공지 발송이 있으면 전달 성공률을 퍼센트로 표시한다`() {
        val embeds = WeeklyReportEmbed.build(sampleReport(deliverySuccessRate = 97))

        assertEquals("97%", fieldValue(embeds[2], "공지 전달 성공률"))
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
