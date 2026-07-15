package com.depromeet.piki.metrics.report

import com.depromeet.piki.metrics.dashboard.MetricsSnapshot
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter

// WeeklyReport → Discord 채널 게시용 embed 3장(성장·활동·건강도). 순수 변환 — DB·Spring 의존 없이 단위로 검증된다.
// 채널 메시지 바디는 { "embeds": [...] } 라 인터랙션 응답(type 4)과 달리 embed 객체만 만든다.
object WeeklyReportEmbed {
    private const val COLOR_GROWTH = 0x2ECC71 // 초록
    private const val COLOR_ACTIVITY = 0x5865F2 // 파랑(blurple)
    private const val COLOR_HEALTH = 0xF39C12 // 앰버
    private val MD = DateTimeFormatter.ofPattern("MM/dd")

    fun build(report: WeeklyReport): List<Map<String, Any>> = listOf(growthCard(report), activityCard(report), healthCard(report))

    private fun growthCard(r: WeeklyReport): Map<String, Any> {
        val fields =
            listOf(
                field("👤 신규 가입", "${r.signupNew} ${trend(r.signupNewTrend)}\n회원 ${r.members} · 게스트 ${r.guests}"),
                field("🔄 전환", "${r.conversions}명\n게스트→회원"),
                field("🌱 실질 증가", "${signed(r.netGrowth)}\n신규 ${r.signupNew} · 탈퇴 ${r.withdrawals}"),
                field("📈 D1 리텐션", "${r.d1Rate}%"),
                field("☀️ DAU peak", dauPeak(r.dauSeries)),
                field("📅 WAU", "${r.wau}"),
                field("📊 일별 DAU", dauTrend(r.dauSeries), inline = false),
                // 전체 폭(inline=false)으로 둬 "카카오 N · 구글 N · 애플 N" 이 줄바꿈 없이 한 줄에 나온다.
                field("🔑 주간 provider", providerCounts(r.weeklyProvider), inline = false),
            )
        return card(COLOR_GROWTH, "📊 PiKi 주간 리포트", fields, footer = cumulativeFooter(r))
            .plus("description" to "${r.weekLabel} · KST")
    }

    private fun activityCard(r: WeeklyReport): Map<String, Any> {
        val fields =
            listOf(
                field("위시 담기", "${r.wishTotal} ${trend(r.wishTotalTrend)}\nURL ${r.wishUrl} · 이미지 ${r.wishImage}"),
                field("파싱 성공률", "${r.parseSuccessRate}%"),
                field("토너먼트 생성", "${r.tournamentCreated} ${trend(r.tournamentCreatedTrend)}"),
                field("참가자", "${r.participants}"),
                // "완료" = 참가자가 자기 토너먼트를 끝까지 완주(tournament_users.completed_at). 토너먼트 우승 확정이 아니라 참가자 단위다.
                // 분자(완료: completed_at 기준 행 수)와 분모(참가: created_at 기준 distinct 유저)의 앵커·단위가 달라 근사치라 라벨에 명시한다.
                field("참가자 완주율", "${r.completionRate}%\n완주 기준·근사"),
                field("플레이", "${r.plays}"),
            )
        return card(COLOR_ACTIVITY, "🛍️ 활동", fields)
    }

    private fun healthCard(r: WeeklyReport): Map<String, Any> {
        val attempts = r.avgAttempts?.let { "%.1f".format(it) } ?: "—"
        val fields =
            listOf(
                field("파싱 실패율", "${r.parseFailRate}%\n평균 시도 $attempts"),
                // 개인 푸시(notifications 테이블) — 발송 수·근사 CTR 은 같은 대상이라 한 필드로 묶는다.
                // 아래 "공지 전달"(announcements 테이블)과는 다른 발송이므로 라벨로 명확히 가른다(같은 카드에 나란히 둬 오독되던 것 정정).
                field("푸시 알림", "${r.pushSent}건 발송\n근사 CTR ${r.ctrApproxPct}%"),
                // 공지(announcements)는 위 개인 푸시와 다른 브로드캐스트 발송이다. 0건이면 "0%"(전부 실패) 오해를 피해 "금주 공지 없음".
                field("공지 전달 성공률", r.deliverySuccessRate?.let { "$it%" } ?: "금주 공지 없음"),
            )
        // 마지막 카드 footer(리포트 맨 아래 작은 글씨)에 증감 화살표 범례를 함께 둔다 — ▲▼ 가 전주 대비임을 독자가 알 수 있게.
        val footer =
            "📅 최근 30일 (${r.month30Label}) — 신규 ${r.d30SignupNew} · 위시 ${r.d30WishTotal} · 토너먼트 ${r.d30TournamentCreated}\n" +
                "▲▼ 는 전주(직전 완결 주) 대비 증감률"
        return card(COLOR_HEALTH, "🔧 건강도 · 전달", fields, footer = footer)
    }

    // ▲12% / ▼5% / 신규 / (전주·현재 모두 0이면 빈 문자열)
    private fun trend(t: Trend): String {
        if (t.isNew) return "신규"
        val pct = t.pct ?: return ""
        return if (pct >= 0) "▲${pct}%" else "▼${-pct}%"
    }

    private fun signed(v: Long): String = if (v >= 0) "+$v" else "$v"

    private fun dauPeak(series: List<MetricsSnapshot.DateCount>): String {
        val peak = series.maxByOrNull { it.count } ?: return "0"
        return "${peak.count} (${peak.date.format(MD)} ${weekday(peak.date.dayOfWeek)})"
    }

    private fun dauTrend(series: List<MetricsSnapshot.DateCount>): String =
        if (series.isEmpty()) "—" else series.joinToString(" ") { "${weekday(it.date.dayOfWeek)}${it.count}" }

    private fun providerCounts(p: Map<String, Long>): String =
        "카카오 ${p["KAKAO"] ?: 0} · 구글 ${p["GOOGLE"] ?: 0} · 애플 ${p["APPLE"] ?: 0}"

    private fun cumulativeFooter(r: WeeklyReport): String {
        val p = r.cumulativeProviderPct
        // "누적 N명"은 게스트 포함 전체 유저, provider %는 회원(user_details) 구성비라 모집단이 다르다 — "회원 구성"으로 % 기준을 명시한다.
        return "누적 ${"%,d".format(r.cumulativeUsers)}명 · 회원 구성 카카오 ${p["KAKAO"] ?: 0}% 구글 ${p["GOOGLE"] ?: 0}% 애플 ${p["APPLE"] ?: 0}%"
    }

    private fun weekday(d: DayOfWeek): String =
        when (d) {
            DayOfWeek.MONDAY -> "월"
            DayOfWeek.TUESDAY -> "화"
            DayOfWeek.WEDNESDAY -> "수"
            DayOfWeek.THURSDAY -> "목"
            DayOfWeek.FRIDAY -> "금"
            DayOfWeek.SATURDAY -> "토"
            DayOfWeek.SUNDAY -> "일"
        }

    private fun field(
        name: String,
        value: String,
        inline: Boolean = true,
    ): Map<String, Any> = mapOf("name" to name, "value" to value, "inline" to inline)

    private fun card(
        color: Int,
        title: String,
        fields: List<Map<String, Any>>,
        footer: String? = null,
    ): Map<String, Any> {
        val base = mutableMapOf<String, Any>("title" to title, "color" to color, "fields" to fields)
        footer?.let { base["footer"] = mapOf("text" to it) }
        return base
    }
}
