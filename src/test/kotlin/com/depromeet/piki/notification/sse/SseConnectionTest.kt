package com.depromeet.piki.notification.sse

import org.junit.jupiter.api.Test
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SseConnectionTest {
    private val threshold = Duration.ofSeconds(60)
    private val t0 = Instant.parse("2026-09-08T00:00:00Z")

    @Test
    fun `연결마다 다른 번호가 부여된다`() {
        val userId = UUID.randomUUID()

        val first = SseConnection(userId, SseEmitter())
        val second = SseConnection(userId, SseEmitter())

        assertNotEquals(first.id, second.id)
    }

    @Test
    fun `하트비트를 한 번도 안 보낸 연결은 아무리 오래돼도 결측이 아니다`() {
        val connection = SseConnection(UUID.randomUUID(), SseEmitter())

        assertNull(connection.lastHeartbeatAt)
        assertFalse(connection.isStale(t0.plusSeconds(3600), threshold))
    }

    @Test
    fun `하트비트를 보낸 뒤로는 임계값과 같은 간격까지 결측이 아니고 넘어야 결측이다`() {
        val connection = SseConnection(UUID.randomUUID(), SseEmitter())
        connection.touch(t0)

        assertEquals(t0, connection.lastHeartbeatAt)
        assertFalse(connection.isStale(t0.plus(threshold), threshold))
        assertTrue(connection.isStale(t0.plus(threshold).plusMillis(1), threshold))
    }

    @Test
    fun `다시 touch 하면 결측 판정이 뒤로 밀린다`() {
        val connection = SseConnection(UUID.randomUUID(), SseEmitter())
        connection.touch(t0)

        connection.touch(t0.plusSeconds(50))

        assertFalse(connection.isStale(t0.plusSeconds(100), threshold))
        assertTrue(connection.isStale(t0.plusSeconds(111), threshold))
    }
}
