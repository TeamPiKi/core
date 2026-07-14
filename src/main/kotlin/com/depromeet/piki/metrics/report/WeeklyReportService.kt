package com.depromeet.piki.metrics.report

import com.depromeet.piki.admin.config.AdminProperties
import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import com.depromeet.piki.metrics.dashboard.MetricsRepository
import com.depromeet.piki.metrics.dashboard.MetricsService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// 주간 리포트 발송 결과 — 수동 발사 화면이 성공/미설정/실패를 구분해 표시한다.
// SENT: 실제 게시 성공 · SKIPPED: 채널 id·봇 토큰 미설정으로 게시 안 함(설정 문제) · FAILED: Discord 전송 실패(전송 문제).
enum class ReportOutcome { SENT, SKIPPED, FAILED }

// 주간 지표 리포트 오케스트레이션. 지난 완결 주 기준으로 현재·전주·30일 스냅샷과 추가 지표를 모아 embed 로 조립해 Discord 에 게시한다.
// 외부 호출(전송)은 트랜잭션 밖 — 스냅샷 조회(MetricsService 내부 짧은 readOnly)와 분리한다.
// admin 켜진 환경에서만 뜬다 — outbound 경로(HttpDiscordMessageSender)가 @ConditionalOnAdminEnabled 라 이 서비스도 같은 조건이어야
// admin 꺼진 부팅(ADMIN_ENABLED 미설정)에서 sender 빈 부재로 컨텍스트가 깨지지 않는다(다른 admin 서비스와 동일 패턴).
@Service
@ConditionalOnAdminEnabled
class WeeklyReportService(
    private val metricsService: MetricsService,
    private val metricsRepository: MetricsRepository,
    private val sender: DiscordMessageSender,
    private val adminProperties: AdminProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 발송 시각(now, KST) 기준 지난 완결 주 리포트를 게시한다. 스케줄러·수동 엔드포인트 공통 진입점.
    // 결과를 돌려줘 수동 발사 화면이 성공/미설정/실패를 정확히 표시하게 한다(성공 배너가 거짓말하지 않도록).
    fun sendLastCompleteWeek(now: LocalDateTime = LocalDateTime.now(KST)): ReportOutcome {
        val channelId = adminProperties.discordMetricsChannelId
        // 채널 id·봇 토큰 중 하나라도 비면 게시 자체가 불가 — 전송 실패가 아니라 설정 누락이므로 SKIPPED 로 구분한다.
        if (channelId.isBlank() || adminProperties.discordBotToken.isBlank()) {
            log.warn("Discord metrics 채널 id 또는 봇 토큰 미설정 — 주간 리포트 게시 skip")
            return ReportOutcome.SKIPPED
        }

        val week = ReportWindow.lastCompleteWeek(now)
        val prev = ReportWindow.previousWeek(week)
        val d30 = ReportWindow.trailing30d(week.to)

        val cur = metricsService.snapshot(week.from, week.to, EXCLUDE_INTERNAL)
        val prevSnap = metricsService.snapshot(prev.from, prev.to, EXCLUDE_INTERNAL)
        val d30Snap = metricsService.snapshot(d30.from, d30.to, EXCLUDE_INTERNAL)

        // 추가 지표 — 시각 컬럼은 UTC 저장이라 MetricsService.snapshot 과 동일하게 KST→UTC 변환해 조회한다.
        val cumulativeProvider = metricsRepository.countCumulativeByProvider(toUtc(week.to), EXCLUDE_INTERNAL)
        val wau = metricsRepository.countWeeklyActiveUsers(week.from.toLocalDate(), week.to.minusSeconds(1).toLocalDate(), EXCLUDE_INTERNAL)
        val withdrawals = metricsRepository.countWithdrawals(toUtc(week.from), toUtc(week.to), EXCLUDE_INTERNAL)
        val avgAttempts = metricsRepository.avgParsingAttempts(toUtc(week.from), toUtc(week.to))

        val report =
            WeeklyReport.of(
                weekLabel = weekLabel(week),
                month30Label = "${d30.from.format(MD)} ~ ${week.to.minusDays(1).format(MD)}",
                cur = cur,
                prev = prevSnap,
                d30 = d30Snap,
                cumulativeProvider = cumulativeProvider,
                wau = wau,
                withdrawals = withdrawals,
                avgAttempts = avgAttempts,
            )

        return if (sender.send(channelId, WeeklyReportEmbed.build(report))) ReportOutcome.SENT else ReportOutcome.FAILED
    }

    // "07/06(월) ~ 07/12(일)" — to 는 반열림 경계라 마지막 날은 to-1일.
    private fun weekLabel(week: Window): String {
        val start = week.from
        val end = week.to.minusDays(1)
        return "${start.format(MD)}(${weekday(start)}) ~ ${end.format(MD)}(${weekday(end)})"
    }

    private fun weekday(dt: LocalDateTime): String =
        when (dt.dayOfWeek) {
            java.time.DayOfWeek.MONDAY -> "월"
            java.time.DayOfWeek.TUESDAY -> "화"
            java.time.DayOfWeek.WEDNESDAY -> "수"
            java.time.DayOfWeek.THURSDAY -> "목"
            java.time.DayOfWeek.FRIDAY -> "금"
            java.time.DayOfWeek.SATURDAY -> "토"
            java.time.DayOfWeek.SUNDAY -> "일"
        }

    private fun toUtc(kst: LocalDateTime): LocalDateTime = kst.atZone(KST).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
        private val MD = DateTimeFormatter.ofPattern("MM/dd")
        private const val EXCLUDE_INTERNAL = true // 실유저 지표만(개발진 제외)
    }
}
