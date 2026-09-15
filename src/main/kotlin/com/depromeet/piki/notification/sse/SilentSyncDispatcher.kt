package com.depromeet.piki.notification.sse

import com.depromeet.piki.common.config.AsyncConfig
import com.depromeet.piki.notification.controller.dto.SilentSyncPayload
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.util.UUID

// 읽음 응답 스레드에서 deliverSilentSync 를 직접 부르면 느린 SSE 소켓의 send 블로킹이 응답 latency 를 좌우하고,
// 거기서 throw 하면 이미 커밋된 읽음이 500 이 되며 뒤따르는 FCM syncBadge 까지 건너뛴다.
// 별도 빈인 이유는 self-invocation 회피 - 같은 빈 안의 @Async 직접 호출은 proxy 를 안 거쳐 동기로 돈다.
@Component
class SilentSyncDispatcher(
    private val localDelivery: LocalSseDelivery,
) {
    @Async(AsyncConfig.NOTIFICATION_EXECUTOR)
    fun dispatch(
        userIds: Collection<UUID>,
        payload: SilentSyncPayload,
    ) {
        localDelivery.deliverSilentSync(userIds, payload)
    }
}
