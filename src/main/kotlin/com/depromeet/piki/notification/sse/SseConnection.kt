package com.depromeet.piki.notification.sse

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Duration
import java.time.Instant
import java.util.UUID

class SseConnection(
    val userId: UUID,
    val emitter: SseEmitter,
) {
    val id: UUID = UUID.randomUUID()

    // null = 클라이언트 하트비트를 한 번도 안 보낸 연결(구 버전 앱). 결측 판정 대상이 아니다.
    @Volatile
    var lastHeartbeatAt: Instant? = null
        private set

    fun touch(now: Instant) {
        lastHeartbeatAt = now
    }

    fun isStale(
        now: Instant,
        threshold: Duration,
    ): Boolean {
        val lastAt = lastHeartbeatAt ?: return false
        return Duration.between(lastAt, now) > threshold
    }
}
