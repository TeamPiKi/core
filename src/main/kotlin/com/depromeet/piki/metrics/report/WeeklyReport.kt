package com.depromeet.piki.metrics.report

import com.depromeet.piki.metrics.dashboard.MetricsSnapshot

// 전주 대비 증감. prev==0 이면 비율이 정의되지 않아 pct=null, cur>0 이면 신규(isNew) 로 구분한다
// (MetricsSnapshot.PeriodComparison.Row 와 같은 규칙).
data class Trend(
    val pct: Int?,
    val isNew: Boolean,
) {
    companion object {
        fun of(
            prev: Long,
            cur: Long,
        ): Trend = if (prev == 0L) Trend(null, cur > 0L) else Trend((((cur - prev) * 100) / prev).toInt(), false)
    }
}

// Discord 주간 리포트의 플랫 표현. 모든 값은 표시 직전 형태(개수·%·시계열)로 이미 추출·계산돼 있고,
// WeeklyReportEmbed 는 이 모델만 보고 카드를 렌더한다(DB·스냅샷 구조에 의존하지 않는다).
data class WeeklyReport(
    val weekLabel: String, // "07/06(월) ~ 07/12(일)"
    val month30Label: String, // "06/13 ~ 07/12"
    // 성장
    val signupNew: Long,
    val signupNewTrend: Trend,
    val members: Long,
    val guests: Long,
    val conversions: Long,
    val withdrawals: Long,
    val d1Rate: Int,
    val dauSeries: List<MetricsSnapshot.DateCount>,
    val wau: Long,
    val weeklyProvider: Map<String, Long>, // 주간 신규 회원 provider 개수
    val cumulativeUsers: Long,
    val cumulativeProviderPct: Map<String, Int>, // 누적 회원 provider %
    // 활동
    val wishTotal: Long,
    val wishTotalTrend: Trend,
    val wishUrl: Long,
    val wishImage: Long,
    val parseSuccessRate: Int,
    val tournamentCreated: Long,
    val tournamentCreatedTrend: Trend,
    val participants: Long,
    val completionRate: Int,
    val plays: Long,
    // 건강도 · 전달
    val parseFailRate: Int,
    val avgAttempts: Double?,
    val pushSent: Long,
    val deliverySuccessRate: Int,
    val ctrApproxPct: Int,
    // 30일 요약
    val d30SignupNew: Long,
    val d30WishTotal: Long,
    val d30TournamentCreated: Long,
) {
    val netGrowth: Long get() = signupNew - withdrawals

    companion object {
        private val PROVIDERS = listOf("KAKAO", "GOOGLE", "APPLE")

        // 스냅샷 3개(현재 주·전주·30일)와 추가 지표를 플랫 모델로 조립한다.
        // 순수 함수 — DB 접근은 WeeklyReportService 가 끝낸 뒤 결과만 넘긴다.
        fun of(
            weekLabel: String,
            month30Label: String,
            cur: MetricsSnapshot,
            prev: MetricsSnapshot,
            d30: MetricsSnapshot,
            cumulativeProvider: Map<String, Long>,
            wau: Long,
            withdrawals: Long,
            avgAttempts: Double?,
        ): WeeklyReport {
            // 주의: cumulativeUsers 는 users(게스트 포함) 기준, provider % 는 user_details(회원 전용) 기준이라 모집단이 다르다.
            // %는 provider 합 대비 비율이라 내부적으로 정합하며, "누적 N명"(전체 유저)을 partition 하지 않는다(회원 구성비일 뿐).
            val cumulativeTotal = PROVIDERS.sumOf { cumulativeProvider[it] ?: 0L }
            val cumulativePct =
                PROVIDERS.associateWith { MetricsSnapshot.pct(cumulativeProvider[it] ?: 0L, cumulativeTotal) }
            return WeeklyReport(
                weekLabel = weekLabel,
                month30Label = month30Label,
                signupNew = cur.signup.within,
                signupNewTrend = Trend.of(prev.signup.within, cur.signup.within),
                members = cur.signup.withinMembers,
                guests = cur.signup.withinGuests,
                conversions = cur.signup.guestToMemberConversions,
                withdrawals = withdrawals,
                d1Rate = cur.retention.d1Rate,
                dauSeries = cur.retention.dau,
                wau = wau,
                weeklyProvider = cur.signup.byProvider,
                cumulativeUsers = cur.signup.before + cur.signup.within,
                cumulativeProviderPct = cumulativePct,
                wishTotal = cur.wish.total,
                wishTotalTrend = Trend.of(prev.wish.total, cur.wish.total),
                wishUrl = cur.wish.fromUrl,
                wishImage = cur.wish.fromImage,
                parseSuccessRate = cur.wish.parseSuccessRate,
                tournamentCreated = cur.tournament.created,
                tournamentCreatedTrend = Trend.of(prev.tournament.created, cur.tournament.created),
                participants = cur.tournament.participants,
                completionRate = MetricsSnapshot.pct(cur.tournament.completed, cur.tournament.participants),
                plays = cur.tournament.plays,
                parseFailRate = 100 - cur.wish.parseSuccessRate,
                avgAttempts = avgAttempts,
                pushSent = cur.push.notificationsTotal,
                deliverySuccessRate =
                    MetricsSnapshot.pct(cur.push.deliverySuccess, cur.push.deliverySuccess + cur.push.deliveryFailure),
                ctrApproxPct = cur.push.ctrApproxPct,
                d30SignupNew = d30.signup.within,
                d30WishTotal = d30.wish.total,
                d30TournamentCreated = d30.tournament.created,
            )
        }
    }
}
