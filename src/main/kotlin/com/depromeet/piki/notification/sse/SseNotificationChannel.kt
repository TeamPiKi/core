package com.depromeet.piki.notification.sse

import com.depromeet.piki.notification.domain.Notification
import com.depromeet.piki.notification.service.NotificationChannel
import org.springframework.stereotype.Component
import java.util.UUID

// 다중 인스턴스로 가면 send 의 이 한 줄만 Redis publish 로 바뀐다.
@Component
class SseNotificationChannel(
    private val localDelivery: LocalSseDelivery,
) : NotificationChannel {
    override fun send(
        userId: UUID,
        notification: Notification,
    ): Int = localDelivery.deliver(userId, notification)
}
