package com.depromeet.piki.metrics.report

import com.depromeet.piki.admin.config.AdminProperties
import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

// 주간 지표 리포트 자동 발송 — 매주 화요일 10:00 KST. 팀 주간 회의(화 22시) 전 리뷰용.
// admin 켜진 환경(운영)에서만 뜬다. 테스트는 admin.scheduler-auto-dispatch=false 로 자동 실행을 끄고
// WeeklyReportService.sendLastCompleteWeek() 를 직접 호출해 결정적으로 검증한다(AnnouncementScheduler 와 동일 패턴).
// 단일 인스턴스 가정 — ShedLock 등 idempotency 가드가 없어, 여러 인스턴스가 동시 dispatch 하면 중복 게시된다.
// 현재 배포는 단일 인스턴스라 무방하나, 다중 인스턴스로 확장되면 ShedLock 도입이 필요하다(ItemParsingScheduler 주석과 동일 전제).
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
        // 자동 발송은 prod 만 — 팀 공용 Discord 채널이라 dev 가 매주 중복·테스트 노이즈를 보내면 안 된다.
        // 채널 id 미설정에 기대지 않고 환경으로 명시 차단한다(누가 dev 채널 id 를 넣어도 자동 발송이 안 켜지게).
        // 수동 발사 버튼은 전 환경에서 가능하나, 미설정 환경에선 SKIPPED 로 끝난다.
        if (adminProperties.environment != "prod") return
        log.info("주간 지표 리포트 자동 발송 시작")
        val outcome = weeklyReportService.sendLastCompleteWeek()
        log.info("주간 지표 리포트 자동 발송 결과: {}", outcome)
    }
}
