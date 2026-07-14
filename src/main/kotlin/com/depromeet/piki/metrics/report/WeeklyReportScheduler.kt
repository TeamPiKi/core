package com.depromeet.piki.metrics.report

import com.depromeet.piki.admin.config.AdminProperties
import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

// 주간 지표 리포트 자동 발송 — 매주 화요일 10:00 KST. 팀 주간 회의(화 22시) 전 리뷰용.
// admin 켜진 환경(운영)에서만 뜬다. 테스트는 admin.scheduler-auto-dispatch=false 로 자동 실행을 끄고
// WeeklyReportService.sendLastCompleteWeek() 를 직접 호출해 결정적으로 검증한다(AnnouncementScheduler 와 동일 패턴).
@Component
@ConditionalOnAdminEnabled
class WeeklyReportScheduler(
    private val weeklyReportService: WeeklyReportService,
    private val adminProperties: AdminProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0 10 * * TUE", zone = "Asia/Seoul")
    fun poll() {
        if (!adminProperties.schedulerAutoDispatch) return
        log.info("주간 지표 리포트 자동 발송 시작")
        weeklyReportService.sendLastCompleteWeek()
    }
}
