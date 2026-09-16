package com.depromeet.piki.admin.announcement
import com.depromeet.piki.announcement.domain.Announcement
import com.depromeet.piki.announcement.repository.AnnouncementRepository

import com.depromeet.piki.admin.config.AdminProperties
import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime

// 예약 공지 발송 폴링(#489). 발송 시각이 도래한 SCHEDULED 공지를 주기적으로 집어 처리한다.
// admin 켜진 환경(운영)에서만 뜬다 — 예약 발송도 백오피스 기능이라 ConditionalOnAdminEnabled.
//
// overdue 정리: 부팅·배포·런타임 어디서든 "예약시각을 한참 넘겨 도래한"(다운타임 등) 건은 철 지난 공지를 뒤늦게
// 발송하지 않고 MISSED 로 닫는다(유예시간 scheduleGraceWindow 안이면 발송, 넘으면 MISSED). 한 로직으로 세 시점을 흡수한다.
// 중복 집음·다중 인스턴스 경합은 execute/markMissed 내부의 비관적 락(claim)이 직렬화해 막는다.
@Component
@ConditionalOnAdminEnabled
class AnnouncementScheduler(
    private val announcementRepository: AnnouncementRepository,
    private val announcementSendService: AnnouncementSendService,
    private val progressWriter: AnnouncementProgressWriter,
    private val adminProperties: AdminProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = POLL_INTERVAL_MS)
    fun poll() {
        dispatchDue()
    }

    // 도래한 예약을 처리 — 유예시간 안이면 발송, 넘으면 MISSED. (테스트가 직접 호출하는 순수 로직)
    fun dispatchDue() {
        // scheduledAt 은 KST wall-clock 으로 저장되므로 비교도 now(KST). JVM 기본 TZ(UTC) now() 를 쓰면 9시간 늦게 발송된다.
        val now = LocalDateTime.now(Announcement.KST)
        val graceCutoff = now.minus(adminProperties.scheduleGraceWindow)
        val due = announcementRepository.findByStatusAndScheduledAtLessThanEqual(Announcement.STATUS_SCHEDULED, now)
        if (due.isEmpty()) return

        val (overdue, sendable) = due.partition { requireNotNull(it.scheduledAt).isBefore(graceCutoff) }
        if (overdue.isNotEmpty()) {
            log.warn("예약 공지 유예시간 초과 → MISSED {}건 (다운타임 등으로 한참 지난 예약은 자동 발송 안 함)", overdue.size)
            overdue.forEach { progressWriter.markMissed(it.getId()) }
        }
        if (sendable.isNotEmpty()) {
            log.info("예약 공지 발송 — 도래 {}건", sendable.size)
            // execute 는 @Async — 외부 빈 호출이라 proxy 를 거쳐 각자 별도 스레드에서 돈다.
            sendable.forEach { announcementSendService.execute(it.getId(), SYSTEM_ACTOR, clientIp = null) }
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 60_000L
        private const val SYSTEM_ACTOR = "scheduler"
    }
}
