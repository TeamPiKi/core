package com.depromeet.piki.notification.sse

import com.depromeet.piki.notification.domain.Notification
import com.depromeet.piki.notification.service.NotificationChannel
import org.springframework.stereotype.Component
import java.util.UUID

// 알림을 SSE 로 전달하는 채널. @Component 라 dispatcher 의 List<NotificationChannel> 에 자동 합류한다
// (NoOp PushNotificationChannel 과 형제 — dispatcher·엔티티·핸들러는 건드리지 않는다).
//
// send 는 "외부 진입"일 뿐이고 실제 레지스트리 write 는 LocalSseDelivery 에 위임한다(스케일아웃 seam — 미래 Redis Pub/Sub 진입점).
// 단일 인스턴스인 지금은 로컬 delivery 직접 호출이고, 다중 인스턴스로 가면 이 한 줄만 Redis publish 로 바뀐다.
@Component
class SseNotificationChannel(
    private val localDelivery: LocalSseDelivery,
) : NotificationChannel {
    // 실시간 인앱 전달 채널이라 write 성공 수를 그대로 돌려준다 — dispatcher 의 자동읽음(#812) 판단 근거다.
    override fun send(
        userId: UUID,
        notification: Notification,
    ): Int = localDelivery.deliver(userId, notification)
}
