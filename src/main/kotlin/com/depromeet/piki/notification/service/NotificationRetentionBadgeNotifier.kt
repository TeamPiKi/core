package com.depromeet.piki.notification.service

import com.depromeet.piki.common.config.AsyncConfig
import com.depromeet.piki.notification.controller.dto.UnreadCountChanged
import com.depromeet.piki.notification.sse.LocalSseDelivery
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.util.UUID

// 자동삭제로 안읽음이 줄어든 유저들에게 배지(SSE·FCM)를 전파하는 배치. 스케줄러가 이 한 메서드만 호출하고, 전체 fan-out 이
// notificationExecutor 워커 "하나" 위에서 순차로 돈다.
//
// 왜 유저당 @Async 를 안 던지나: 단건·모두 삭제(NotificationDeleteOrchestrator)는 영향 유저가 1명이라 유저당 @Async 로 충분하지만,
// 자동삭제 첫 실행은 14일 넘긴 백로그가 수백~수천 유저에 몰릴 수 있다. 유저당 태스크(유저×2)를 한꺼번에 던지면
// notificationExecutor(pool 4·queue 200·포화 시 drop)를 넘겨 배지가 대량 유실되고, 같은 실행기를 쓰는 실시간 알림까지 굶는다.
// (500명 실측: 유저당 던지기 = FCM 전달률 21.6%.) 순차 처리라 어느 순간에도 실행기 점유 태스크가 1개뿐 → drop 0, 실시간 알림 보존.
//
// 한 유저 전달 실패가 배치를 끊지 않게 각 유저를 방어한다(FCM 은 syncBadgeBlocking 이 자체 흡수, SSE 는 여기서 흡수).
// 배치 자체가 실행기에서 거부되면(이미 포화) 로그만 남고 이번 회차 배지 동기화를 건너뛴다 — 못 받은 기기는 재진입 시 GET 으로 보정된다.
@Component
class NotificationRetentionBadgeNotifier(
    private val localDelivery: LocalSseDelivery,
    private val pushNotificationChannel: PushNotificationChannel,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async(AsyncConfig.NOTIFICATION_EXECUTOR)
    fun notifyAll(unreadByUser: Map<UUID, Long>) {
        if (unreadByUser.isEmpty()) return
        unreadByUser.forEach { (userId, unread) ->
            // 온라인(열린 SSE) 기기 인앱 배지.
            runCatching { localDelivery.deliverSilentSync(listOf(userId), UnreadCountChanged.of(unread)) }
                .onFailure { log.warn("자동삭제 SSE 배지 동기화 실패 userId={}", userId, it) }
            // 오프라인 기기 OS 아이콘 배지 (syncBadgeBlocking 이 예외를 자체 흡수).
            pushNotificationChannel.syncBadgeBlocking(userId, badgeCountOf(unread))
        }
        log.info("자동삭제 배지 동기화 완료 — 대상 {}명 순차 전파", unreadByUser.size)
    }
}
