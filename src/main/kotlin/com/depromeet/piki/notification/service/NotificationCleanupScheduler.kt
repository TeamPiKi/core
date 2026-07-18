package com.depromeet.piki.notification.service

import com.depromeet.piki.notification.config.NotificationProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime

// N일 자동삭제 스케줄러 — 보존 기간(notification.retention-days)을 넘긴 알림을 1일 1회 하드삭제한다.
// admin 백오피스와 무관한 코어 유지보수라 ConditionalOnAdminEnabled 를 붙이지 않는다(AnnouncementScheduler 와 다른 점).
// 스케줄러는 얇게 — cutoff 계산 + 위임만 한다. 삭제·트랜잭션 경계는 NotificationRetentionService 가, 배지 fan-out 은
// NotificationRetentionBadgeNotifier 가 진다(둘 다 테스트가 직접 호출). 삭제로 안읽음이 줄면 배지를 최신으로 맞춰야
// 하므로(사라진 unread 가 REST unreadCount·SSE·FCM 배지에 stale 로 남지 않게), 삭제 후 영향 유저에게 배지를 전파한다.
// 배지 전파는 단일 @Async 배치(notifier)라 스케줄 스레드를 잡지 않는다.
@Component
class NotificationCleanupScheduler(
    private val notificationRetentionService: NotificationRetentionService,
    private val notificationProperties: NotificationProperties,
    private val badgeNotifier: NotificationRetentionBadgeNotifier,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 기본 매일 04:00 KST. 값은 notification.cleanup-cron 으로 덮을 수 있다(placeholder 기본값으로 미설정 시에도 부팅 안전).
    // zone 을 KST 로 고정한다 — 이 앱의 JVM 기본 TZ 는 UTC 라(로그 표시만 KST), 미지정 시 04:00 UTC(13:00 KST)에 돈다.
    // DailyActivityRecorder·WeeklyReportScheduler 등 다른 cron 스케줄러와 동일하게 zone="Asia/Seoul" 로 맞춘다.
    @Scheduled(cron = "\${notification.cleanup-cron:0 0 4 * * *}", zone = "Asia/Seoul")
    fun cleanup() {
        // cutoff 은 created_at(JVM 기본 TZ 의 @CreatedDate)과 같은 기준의 now 에서 빼야 비교가 어긋나지 않는다.
        val cutoff = LocalDateTime.now().minusDays(notificationProperties.retentionDays)
        val result = notificationRetentionService.purgeOlderThan(cutoff)
        // 커밋 후 배지 동기화 위임 — 단일 @Async 배치가 영향 유저를 순차 전파한다(대량 백로그에도 실행기 포화 없이 drop 0).
        badgeNotifier.notifyAll(result.affectedUnreadByUser)
        log.info(
            "알림 자동삭제 스케줄 실행 — 보존 {}일, 삭제 {}건, 배지대상 {}명",
            notificationProperties.retentionDays,
            result.deletedCount,
            result.affectedUnreadByUser.size,
        )
    }
}
