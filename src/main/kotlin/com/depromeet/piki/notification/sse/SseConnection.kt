package com.depromeet.piki.notification.sse

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Duration
import java.time.Instant
import java.util.UUID

class SseConnection(
    val userId: UUID,
    val emitter: SseEmitter,
    val openedAt: Instant = Instant.now(),
) {
    val id: UUID = UUID.randomUUID()

    // 등록 시각에서 시작해 클라이언트 하트비트가 갱신한다. 첫 하트비트가 임계값 안에 없으면 결측이다.
    // 요청 스레드가 쓰고 스케줄러 스레드가 읽으므로 가시성 보장이 필요하다.
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
