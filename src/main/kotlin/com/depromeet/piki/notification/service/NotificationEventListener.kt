package com.depromeet.piki.notification.service

import com.depromeet.piki.common.config.AsyncConfig
import com.depromeet.piki.common.event.NotificationEvent
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

// 도메인 이벤트를 구독해 NotificationDispatcher 로 위임한다.
// 결합 방향: 알림 -> 도메인 (단방향). 도메인은 알림 패키지를 import 하지 않는다.
// 발행 트랜잭션이 커밋된 뒤(AFTER_COMMIT)에만, 별도 스레드(@Async)에서 디스패치한다 — 롤백 시 발송 안 됨.
//
// **구독은 타입 하나(NotificationEvent 마커)로 받는다.** 예전엔 이벤트 타입마다 오버로드를 하나씩 뒀는데,
// 그 방식은 새 알림을 붙일 때 이벤트·핸들러·리스너 세 곳을 손으로 맞춰야 했고 한 줄만 빠뜨려도 Spring 이
// 구독자 없는 이벤트를 조용히 버려 알림이 통째로 사라졌다 (#961 — 플레이·완료 알림 3종이 그렇게 죽어 있었다).
// 이제 새 알림 이벤트 추가 = 이벤트가 마커를 구현 + 핸들러 빈 추가. 이 파일은 손대지 않는다.
@Component
class NotificationEventListener(
    private val dispatcher: NotificationDispatcher,
) {
    @Async(AsyncConfig.NOTIFICATION_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun on(event: NotificationEvent) {
        dispatcher.dispatch(event)
    }
}
