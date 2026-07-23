package com.depromeet.piki.metrics.dashboard

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

// MetricsSnapshot 의 파생 계산(비율·평균) 분기 — 특히 0 분모 처리를 망라한다. 순수 계산이라 Spring·DB 없이 검증한다.
class MetricsSnapshotTest {
    @Test
    fun `pct 는 분모가 0이면 0, 아니면 내림 정수 퍼센트를 반환한다`() {
        assertEquals(0, MetricsSnapshot.pct(0, 0))
        assertEquals(0, MetricsSnapshot.pct(5, 0))
        assertEquals(50, MetricsSnapshot.pct(5, 10))
        assertEquals(33, MetricsSnapshot.pct(1, 3))
        assertEquals(100, MetricsSnapshot.pct(7, 7))
    }

    @Test
    fun `위시 파싱 성공률은 성공+실패가 0이면 0이다`() {
        assertEquals(0, wish(ready = 0, failed = 0).parseSuccessRate)
        assertEquals(80, wish(ready = 8, failed = 2).parseSuccessRate)
    }

    @Test
    fun `파싱 성공률은 위시-토너먼트 출처별과 전체가 각각 계산된다`() {
        val w = wish(ready = 8, failed = 2, tournamentReady = 3, tournamentFailed = 7)
        assertEquals(80, w.wishParseSuccessRate) // 위시 8/10
        assertEquals(30, w.tournamentParseSuccessRate) // 토너먼트 3/10
        assertEquals(55, w.parseSuccessRate) // 전체 (8+3)/20
        assertEquals(11, w.parsedReady)
        assertEquals(9, w.parsedFailed)
    }

    @Test
    fun `토너먼트 평균 참가자는 생성이 0이면 0, 아니면 소수 1자리다`() {
        assertEquals("0", tournament(created = 0, participants = 0).avgParticipants)
        assertEquals("3.5", tournament(created = 2, participants = 7).avgParticipants)
    }

    @Test
    fun `D1 리텐션율은 코호트 가입이 0이면 0이다`() {
        assertEquals(0, retention(cohort = 0, returned = 0).d1Rate)
        assertEquals(44, retention(cohort = 100, returned = 44).d1Rate)
    }

    @Test
    fun `푸시 CTR 근사는 알림이 0이면 0이다`() {
        assertEquals(0, push(total = 0, read = 0).ctrApproxPct)
        assertEquals(31, push(total = 100, read = 31).ctrApproxPct)
    }

    @Test
    fun `비교 변화율은 직전이 0이면 신규로, 아니면 증감 퍼센트로 표기한다`() {
        val new = PeriodComparison.Row("x", prev = 0, cur = 5)
        assertEquals(null, new.deltaPct)
        assertEquals(true, new.isNew)

        val up = PeriodComparison.Row("x", prev = 100, cur = 312)
        assertEquals(212, up.deltaPct)
        assertEquals(false, up.isNew)

        val down = PeriodComparison.Row("x", prev = 100, cur = 60)
        assertEquals(-40, down.deltaPct)
    }

    private fun wish(
        ready: Long,
        failed: Long,
        tournamentReady: Long = 0,
        tournamentFailed: Long = 0,
    ) = MetricsSnapshot.Wish(
        total = ready + failed,
        fromUrl = 0,
        fromImage = 0,
        activeTotal = 0,
        activeFromUrl = 0,
        activeFromImage = 0,
        wishParsedReady = ready,
        wishParsedFailed = failed,
        tournamentParsedReady = tournamentReady,
        tournamentParsedFailed = tournamentFailed,
    )

    private fun tournament(
        created: Long,
        participants: Long,
    ) = MetricsSnapshot.Tournament(created = created, participants = participants, itemsAdded = 0, completed = 0, plays = 0)

    private fun retention(
        cohort: Long,
        returned: Long,
    ) = MetricsSnapshot.Retention(cohortSignups = cohort, d1Returned = returned, dau = emptyList())

    private fun push(
        total: Long,
        read: Long,
    ) = MetricsSnapshot.Push(byType = emptyMap(), deliverySuccess = 0, deliveryFailure = 0, deliverySkipped = 0, notificationsTotal = total, readApprox = read)
}
