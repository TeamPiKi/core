package com.depromeet.piki.notification.sse

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// 레지스트리는 SSE 연결의 멤버십만 다루므로 Spring·DB 없이 단위로 망라한다(emitter 는 식별자로만 쓰고 전송하지 않는다).
class SseEmitterRegistryTest {
    private val registry = SseEmitterRegistry()
    private val t0 = Instant.parse("2026-09-08T00:00:00Z")

    @Test
    fun `register 한 연결이 connectionsOf 로 조회된다`() {
        val userId = UUID.randomUUID()

        val connection = registry.register(userId, SseEmitter())

        assertEquals(listOf(connection), registry.connectionsOf(userId))
    }

    @Test
    fun `한 유저가 여러 탭으로 접속하면 연결이 모두 보관된다`() {
        val userId = UUID.randomUUID()

        val first = registry.register(userId, SseEmitter())
        val second = registry.register(userId, SseEmitter())

        assertEquals(listOf(first, second), registry.connectionsOf(userId))
    }

    @Test
    fun `unregister 하면 해당 연결만 빠지고 나머지는 남는다`() {
        val userId = UUID.randomUUID()
        val keep = registry.register(userId, SseEmitter())
        val drop = registry.register(userId, SseEmitter())

        registry.unregister(drop)

        assertEquals(listOf(keep), registry.connectionsOf(userId))
    }

    @Test
    fun `마지막 연결을 unregister 하면 그 유저의 연결이 비워진다`() {
        val userId = UUID.randomUUID()
        val connection = registry.register(userId, SseEmitter())

        registry.unregister(connection)

        assertTrue(registry.connectionsOf(userId).isEmpty())
        var visited = 0
        registry.forEach { _ -> visited++ }
        assertEquals(0, visited)
    }

    @Test
    fun `등록되지 않은 유저의 connectionsOf 는 빈 리스트다`() {
        assertTrue(registry.connectionsOf(UUID.randomUUID()).isEmpty())
    }

    @Test
    fun `없는 연결을 unregister 해도 예외 없이 무시된다`() {
        val userId = UUID.randomUUID()
        registry.register(userId, SseEmitter())

        registry.unregister(SseConnection(userId, SseEmitter()))
        registry.unregister(SseConnection(UUID.randomUUID(), SseEmitter()))

        assertEquals(1, registry.connectionsOf(userId).size)
    }

    @Test
    fun `등록이 돌려준 번호로 touch 하면 그 연결의 최근 시각이 갱신된다`() {
        val userId = UUID.randomUUID()
        val connection = registry.register(userId, SseEmitter())

        val touched = registry.touch(userId, connection.id, t0)

        assertTrue(touched)
        assertEquals(t0, connection.lastHeartbeatAt)
    }

    @Test
    fun `모르는 번호나 다른 유저의 번호로 touch 하면 false 이고 시각도 바뀌지 않는다`() {
        val owner = UUID.randomUUID()
        val connection = registry.register(owner, SseEmitter())

        assertFalse(registry.touch(owner, UUID.randomUUID(), t0))
        assertFalse(registry.touch(UUID.randomUUID(), connection.id, t0))
        assertEquals(null, connection.lastHeartbeatAt)
    }

    @Test
    fun `unregister 한 연결의 번호는 더 이상 touch 되지 않는다`() {
        val userId = UUID.randomUUID()
        val connection = registry.register(userId, SseEmitter())

        registry.unregister(connection)

        assertFalse(registry.touch(userId, connection.id, t0))
    }

    @Test
    fun `removeStale 은 하트비트가 임계값 넘게 끊긴 연결만 떼어내 돌려준다`() {
        val userId = UUID.randomUUID()
        val stale = registry.register(userId, SseEmitter())
        val alive = registry.register(userId, SseEmitter())
        val legacy = registry.register(userId, SseEmitter())
        registry.touch(userId, stale.id, t0)
        registry.touch(userId, alive.id, t0.plusSeconds(50))

        val removed = registry.removeStale(t0.plusSeconds(61), Duration.ofSeconds(60))

        assertEquals(listOf(stale), removed)
        assertEquals(listOf(alive, legacy), registry.connectionsOf(userId))
    }

    @Test
    fun `forEach 는 모든 유저의 모든 연결을 순회한다`() {
        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()
        registry.register(userA, SseEmitter())
        registry.register(userA, SseEmitter())
        registry.register(userB, SseEmitter())

        val visited = mutableListOf<UUID>()
        registry.forEach { connection -> visited.add(connection.userId) }

        assertEquals(2, visited.count { it == userA })
        assertEquals(1, visited.count { it == userB })
    }

    @Test
    @Timeout(15, unit = TimeUnit.SECONDS)
    fun `동시 register 와 unregister 가 유실이나 예외 없이 처리된다`() {
        val userId = UUID.randomUUID()
        val count = 50
        val connections = arrayOfNulls<SseConnection>(count)

        runConcurrently(count) { i -> connections[i] = registry.register(userId, SseEmitter()) }
        assertEquals(count, registry.connectionsOf(userId).size)

        runConcurrently(count) { i -> registry.unregister(requireNotNull(connections[i])) }
        assertTrue(registry.connectionsOf(userId).isEmpty())
    }

    // count 개의 작업을 동시에 출발시킨다(2단계 래치). 풀 크기를 count 와 같게 둬야 모든 작업이 동시에
    // start 래치까지 도달한다 — 풀이 작으면 일부 작업이 큐에 묶여 ready 가 0 에 못 닿는 기아 데드락이 난다.
    private fun runConcurrently(
        count: Int,
        action: (Int) -> Unit,
    ) {
        val executor = Executors.newFixedThreadPool(count)
        val ready = CountDownLatch(count)
        val start = CountDownLatch(1)
        try {
            repeat(count) { i ->
                executor.submit {
                    ready.countDown()
                    start.await()
                    action(i)
                }
            }
            ready.await()
            start.countDown()
            executor.shutdown()
            check(executor.awaitTermination(10, TimeUnit.SECONDS)) { "동시 작업이 시간 내 끝나지 않았다" }
        } finally {
            executor.shutdownNow()
        }
    }
}
