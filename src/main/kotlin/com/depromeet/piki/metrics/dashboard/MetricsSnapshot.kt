package com.depromeet.piki.metrics.dashboard

import java.time.LocalDate
import java.time.LocalDateTime

// 운영 통계 대시보드의 한 구간([from, to)) 스냅샷. 모든 수치는 MetricsRepository 의 DB 집계에서 나오고,
// 비율·평균 등 파생값만 여기서 계산한다. signup.before 는 구간 시작 전 누적, 나머지는 구간 내 값이다.
data class MetricsSnapshot(
    val from: LocalDateTime,
    val to: LocalDateTime,
    val signup: Signup,
    val wish: Wish,
    val tournament: Tournament,
    val pushReachableUsers: Long,
    val retention: Retention,
    val push: Push,
    val hourlySignups: List<HourCount>,
) {
    // 차트 막대 스케일(0~100%)용 최댓값.
    val hourlyMax: Long get() = hourlySignups.maxOfOrNull { it.count } ?: 0L

    data class Signup(
        val before: Long,
        val within: Long,
        val withinMembers: Long,
        val withinGuests: Long,
        val byProvider: Map<String, Long>,
        val guestToMemberConversions: Long,
    )

    data class Wish(
        // 유입(flow) — 조회 구간 내 담긴 위시 수(삭제 여부 무관, 담은 사실 기준). 기간 비교·주간 리포트 트렌드에 쓰인다.
        val total: Long,
        val fromUrl: Long,
        val fromImage: Long,
        // 현재 활성 위시(stock) — 구간 무관, deleted_at IS NULL 인 "지금 위시리스트에 살아있는 수".
        val activeTotal: Long,
        val activeFromUrl: Long,
        val activeFromImage: Long,
        // 파싱(추출) 출처별 — 위시(등록+새로고침) vs 토너먼트(전용 아이템). 구간 내 READY/FAILED.
        val wishParsedReady: Long,
        val wishParsedFailed: Long,
        val tournamentParsedReady: Long,
        val tournamentParsedFailed: Long,
    ) {
        val parsedReady: Long get() = wishParsedReady + tournamentParsedReady
        val parsedFailed: Long get() = wishParsedFailed + tournamentParsedFailed
        val parseSuccessRate: Int get() = pct(parsedReady, parsedReady + parsedFailed)
        val wishParseSuccessRate: Int get() = pct(wishParsedReady, wishParsedReady + wishParsedFailed)
        val tournamentParseSuccessRate: Int get() = pct(tournamentParsedReady, tournamentParsedReady + tournamentParsedFailed)
    }

    data class Tournament(
        val created: Long,
        val participants: Long,
        val itemsAdded: Long,
        val completed: Long,
        val plays: Long,
    ) {
        val avgParticipants: String
            get() = if (created == 0L) "0" else "%.1f".format(participants.toDouble() / created)
    }

    data class Retention(
        val cohortSignups: Long,
        val d1Returned: Long,
        val dau: List<DateCount>,
    ) {
        val d1Rate: Int get() = pct(d1Returned, cohortSignups)

        val dauMax: Long get() = dau.maxOfOrNull { it.count } ?: 0L
    }

    // 푸시 히스토리·도달률·근사 클릭률. ctrApprox 는 is_read 기반 근사다(푸시 탭과 알림센터 열람이 섞임).
    data class Push(
        val byType: Map<String, Long>,
        val deliverySuccess: Long,
        val deliveryFailure: Long,
        val deliverySkipped: Long,
        val notificationsTotal: Long,
        val readApprox: Long,
    ) {
        val ctrApproxPct: Int get() = pct(readApprox, notificationsTotal)
    }

    data class HourCount(val hour: Int, val count: Long)

    data class DateCount(val date: LocalDate, val count: Long)

    companion object {
        fun pct(numerator: Long, denominator: Long): Int = if (denominator == 0L) 0 else ((numerator * 100) / denominator).toInt()
    }
}

// 두 구간 비교(현재 구간 vs 직전 동일 길이 구간). 릴리즈·이벤트 전후 변화를 본다.
data class PeriodComparison(
    val prevFrom: LocalDateTime,
    val prevTo: LocalDateTime,
    val curFrom: LocalDateTime,
    val curTo: LocalDateTime,
    val rows: List<Row>,
) {
    data class Row(
        val label: String,
        val prev: Long,
        val cur: Long,
    ) {
        // 변화율(%). 직전이 0이면 비율이 정의되지 않아 null(템플릿이 "신규"/"—"로 표기).
        val deltaPct: Int? get() = if (prev == 0L) null else (((cur - prev) * 100) / prev).toInt()

        val isNew: Boolean get() = prev == 0L && cur > 0L
    }
}
