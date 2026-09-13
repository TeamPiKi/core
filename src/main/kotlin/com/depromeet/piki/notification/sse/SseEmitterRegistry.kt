package com.depromeet.piki.notification.sse

import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

// SseEmitter 는 이 JVM 의 응답 소켓에 묶여 있어 Redis 로 옮길 수 없다. 멀티 인스턴스가 돼도 각 인스턴스가 자기 연결만 든다.
@Component
class SseEmitterRegistry {
    private val connectionsByUser = ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseConnection>>()

    // computeIfAbsent 뒤 밖에서 add 하면 그 사이 unregister 가 빈 리스트를 키째 지워 새 연결이 고아가 된다.
    fun register(
        userId: UUID,
        emitter: SseEmitter,
    ): SseConnection {
        val connection = SseConnection(userId, emitter)
        connectionsByUser.compute(userId) { _, list ->
            (list ?: CopyOnWriteArrayList()).apply { add(connection) }
        }
        return connection
    }

    fun unregister(connection: SseConnection) {
        connectionsByUser.compute(connection.userId) { _, list ->
            list?.apply { remove(connection) }?.takeIf { it.isNotEmpty() }
        }
    }

    fun connectionsOf(userId: UUID): List<SseConnection> = connectionsByUser[userId].orEmpty()

    // 유저 파티션 안에서만 찾으므로 남의 번호는 모르는 번호와 같이 false 다.
    fun touch(
        userId: UUID,
        connectionId: UUID,
        now: Instant,
    ): Boolean {
        val connection = connectionsOf(userId).firstOrNull { it.id == connectionId } ?: return false
        connection.touch(now)
        return true
    }

    fun removeAll(userId: UUID): List<SseConnection> = connectionsByUser.remove(userId).orEmpty()

    fun removeStale(
        now: Instant,
        threshold: Duration,
    ): List<SseConnection> {
        val stale = connectionsByUser.values.flatMap { list -> list.filter { it.isStale(now, threshold) } }
        stale.forEach(::unregister)
        return stale
    }

    fun forEach(action: (SseConnection) -> Unit) {
        connectionsByUser.values.forEach { list -> list.forEach(action) }
    }
}
