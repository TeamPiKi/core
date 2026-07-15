package com.depromeet.piki.notification.service

import com.depromeet.piki.notification.config.NotificationProperties
import com.depromeet.piki.notification.controller.dto.UnreadCountChanged
import com.depromeet.piki.notification.sse.SilentSyncDispatcher
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime

// N일 자동삭제 스케줄러 — 보존 기간(notification.retention-days)을 넘긴 알림을 1일 1회 하드삭제한다.
// admin 백오피스와 무관한 코어 유지보수라 ConditionalOnAdminEnabled 를 붙이지 않는다(AnnouncementScheduler 와 다른 점).
// 삭제 로직·트랜잭션 경계는 NotificationRetentionService 가 지고(테스트는 그쪽을 직접 호출), 스케줄러는 cutoff 계산 +
// 삭제 후 배지 동기화(NotificationDeleteOrchestrator 의 orchestrator 역할)를 맡는다 — 자동삭제도 단건·모두 삭제처럼
// 안읽음이 줄면 배지를 최신으로 맞춰야 하기 때문(삭제로 사라진 unread 가 REST unreadCount·SSE·FCM 배지에 stale 로 남지 않게).
@Component
class NotificationCleanupScheduler(
    private val notificationRetentionService: NotificationRetentionService,
    private val notificationProperties: NotificationProperties,
    private val pushNotificationChannel: PushNotificationChannel,
    private val silentSyncDispatcher: SilentSyncDispatcher,
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

        // 커밋 후 배지 동기화 — 삭제로 안읽음이 준 유저마다 온라인 기기엔 SSE silent-sync, 오프라인 기기엔 FCM 배지.
        // 둘 다 @Async 라 스케줄 스레드를 잡지 않는다(단건·모두 삭제의 deleteAndSyncBadge 와 동일).
        result.affectedUnreadByUser.forEach { (userId, unread) ->
            silentSyncDispatcher.dispatch(listOf(userId), UnreadCountChanged.of(unread))
            pushNotificationChannel.syncBadge(userId, unread.toBadgeCount())
        }
        log.info(
            "알림 자동삭제 스케줄 실행 — 보존 {}일, 삭제 {}건, 배지동기화 {}명",
            notificationProperties.retentionDays,
            result.deletedCount,
            result.affectedUnreadByUser.size,
        )
    }
}
