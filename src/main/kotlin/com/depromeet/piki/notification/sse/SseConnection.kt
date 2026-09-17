package com.depromeet.piki.notification.sse

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Duration
import java.time.Instant
import java.util.UUID

class SseConnection(
    val userId: UUID,
    val emitter: SseEmitter,
    openedAt: Instant = Instant.now(),
) {
    val id: UUID = UUID.randomUUID()

    // 등록 시각에서 시작한다. 첫 하트비트가 임계값 안에 없으면 결측이라, 하트비트 없는 클라이언트는 임계값마다 재연결한다.
    @Volatile
    var lastHeartbeatAt: Instant = openedAt
        private set

    fun touch(now: Instant) {
        lastHeartbeatAt = now
    }

    fun isStale(
        now: Instant,
        threshold: Duration,
    ): Boolean = Duration.between(lastHeartbeatAt, now) > threshold
}
