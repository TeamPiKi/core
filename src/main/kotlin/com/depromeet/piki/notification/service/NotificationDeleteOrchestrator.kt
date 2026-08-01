package com.depromeet.piki.notification.service

import com.depromeet.piki.notification.controller.dto.UnreadCountChanged
import com.depromeet.piki.notification.service.dto.NotificationDeleteCommand
import com.depromeet.piki.notification.sse.SilentSyncDispatcher
import org.springframework.stereotype.Component
import java.util.UUID

// 삭제 처리 + 갱신 badge 동기화를 묶는 얇은 오케스트레이터. 읽음(NotificationReadOrchestrator)과 대칭이다 —
// 삭제로 안읽음 알림이 사라지면 badge 도 줄어야 하므로, 삭제 후 안읽음 수를 두 경로로 다른 기기에 맞춘다.
// 트랜잭션이 없다(@Transactional 미부착) — 삭제 DELETE 는 NotificationService.delete(별도 빈의 @Transactional)가
// 짧은 트랜잭션으로 커밋하고, badge 동기화(SSE·FCM) 둘 다 @Async 라 그 커밋 이후 응답 경로 밖에서 돈다.
@Component
class NotificationDeleteOrchestrator(
    private val notificationService: NotificationService,
    private val pushNotificationChannel: PushNotificationChannel,
    private val silentSyncDispatcher: SilentSyncDispatcher,
) {
    // 삭제 후 갱신 안읽음 수를 반환하고(삭제한 기기는 응답 body 로 즉시 badge 미러링), 같은 유저의 다른 기기엔
    // 두 경로로 badge 를 맞춘다: 온라인(열린 SSE) 기기는 silent-sync(UNREAD_COUNT_CHANGED)로 인앱 배지를,
    // 오프라인 기기는 FCM silent 푸시로 OS 아이콘 badge 를. 읽음 flow(readAndSyncBadge)와 완전히 대칭이다.
    fun deleteAndSyncBadge(
        userId: UUID,
        command: NotificationDeleteCommand,
    ): Long {
        val unread = notificationService.delete(userId, command)
        silentSyncDispatcher.dispatch(listOf(userId), UnreadCountChanged.of(unread))
        pushNotificationChannel.syncBadge(userId, unread.toBadgeCount())
        return unread
    }
}
