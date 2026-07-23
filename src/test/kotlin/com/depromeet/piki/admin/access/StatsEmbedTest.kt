package com.depromeet.piki.admin.access

import com.depromeet.piki.metrics.dashboard.MetricsSnapshot
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals

// /stats 슬래시커맨드의 embed 조립은 DB·Spring 없이 순수 변환이라 단위로 망라한다.
// (집계 자체는 MetricsDashboardIntegrationTest 가 통합으로 검증하므로 여기선 "스냅샷 → embed 필드" 매핑만 본다.)
class StatsEmbedTest {
    private fun snapshot() =
        MetricsSnapshot(
            from = LocalDateTime.of(2026, 2, 1, 0, 0),
            to = LocalDateTime.of(2026, 2, 28, 0, 0),
            signup =
                MetricsSnapshot.Signup(
                    before = 1000,
                    within = 120,
                    withinMembers = 80,
                    withinGuests = 40,
                    byProvider = mapOf("kakao" to 70L, "apple" to 10L),
                    guestToMemberConversions = 15,
                ),
            wish =
                MetricsSnapshot.Wish(
                    total = 300,
                    fromUrl = 200,
                    fromImage = 100,
                    activeTotal = 300,
                    activeFromUrl = 200,
                    activeFromImage = 100,
                    wishParsedReady = 270,
                    wishParsedFailed = 30,
                    tournamentParsedReady = 0,
                    tournamentParsedFailed = 0,
                ),
            tournament = MetricsSnapshot.Tournament(created = 50, participants = 180, itemsAdded = 600, completed = 40, plays = 220),
            pushReachableUsers = 900,
            retention = MetricsSnapshot.Retention(cohortSignups = 120, d1Returned = 48, dau = emptyList()),
            push =
                MetricsSnapshot.Push(
                    byType = mapOf("ALERT" to 100L),
                    deliverySuccess = 95,
                    deliveryFailure = 5,
                    deliverySkipped = 0,
                    notificationsTotal = 100,
                    readApprox = 40,
                ),
            hourlySignups = emptyList(),
        )

    @Test
    fun `가입 metric 은 신규 가입·회원·게스트·전환 수를 embed 필드로 담는다`() {
        val fields = fieldsOf(StatsEmbed.build(StatsMetric.SIGNUP, snapshot(), "최근 7일"))

        assertEquals("120", fields["신규 가입"])
        assertEquals("80", fields["회원"])
        assertEquals("40", fields["게스트"])
        assertEquals("15", fields["게스트→회원 전환"])
    }

    @Test
    fun `위시 metric 은 총 담기·출처·파싱 성공률을 담는다`() {
        val fields = fieldsOf(StatsEmbed.build(StatsMetric.WISH, snapshot(), "최근 7일"))

        assertEquals("300", fields["위시 담기"])
        assertEquals("200", fields["URL"])
        assertEquals("100", fields["이미지"])
        assertEquals("90%", fields["파싱 성공률"])
    }

    @Test
    fun `토너먼트 metric 은 생성·참가·완료·플레이·평균 참가를 담는다`() {
        val fields = fieldsOf(StatsEmbed.build(StatsMetric.TOURNAMENT, snapshot(), "최근 7일"))

        assertEquals("50", fields["생성"])
        assertEquals("180", fields["참가자"])
        assertEquals("40", fields["완료"])
        assertEquals("220", fields["플레이"])
        assertEquals("3.6", fields["평균 참가"])
    }

    @Test
    fun `푸시 metric 은 총 발송·성공·실패·근사 CTR 을 담는다`() {
        val fields = fieldsOf(StatsEmbed.build(StatsMetric.PUSH, snapshot(), "최근 7일"))

        assertEquals("100", fields["총 발송"])
        assertEquals("95", fields["성공"])
        assertEquals("5", fields["실패"])
        assertEquals("40%", fields["근사 CTR"])
    }

    @Test
    fun `요약 metric 은 가입·위시·토너먼트·푸시 대표값을 한 embed 로 담는다`() {
        val fields = fieldsOf(StatsEmbed.build(StatsMetric.SUMMARY, snapshot(), "최근 7일"))

        assertEquals("120", fields["신규 가입"])
        assertEquals("300", fields["위시 담기"])
        assertEquals("50", fields["토너먼트 생성"])
        assertEquals("100", fields["푸시 발송"])
    }

    @Test
    fun `embed title 은 metric 이름과 기간 라벨을 함께 표시한다`() {
        assertEquals("📊 요약 · 최근 7일", titleOf(StatsEmbed.build(StatsMetric.SUMMARY, snapshot(), "최근 7일")))
        assertEquals("📊 가입 · 오늘", titleOf(StatsEmbed.build(StatsMetric.SIGNUP, snapshot(), "오늘")))
        assertEquals("📊 위시 · 어제", titleOf(StatsEmbed.build(StatsMetric.WISH, snapshot(), "어제")))
    }

    // Discord 인터랙션 응답(type 4)의 data.embeds[0].fields 를 name→value 맵으로 뽑는다.
    @Suppress("UNCHECKED_CAST")
    private fun fieldsOf(response: Map<String, Any>): Map<String, String> {
        val fields = embed(response)["fields"] as List<Map<String, Any>>
        return fields.associate { (it["name"] as String) to (it["value"] as String) }
    }

    private fun titleOf(response: Map<String, Any>): String = embed(response)["title"] as String

    @Suppress("UNCHECKED_CAST")
    private fun embed(response: Map<String, Any>): Map<String, Any> {
        val data = response["data"] as Map<String, Any>
        val embeds = data["embeds"] as List<Map<String, Any>>
        return embeds.first()
    }
}
