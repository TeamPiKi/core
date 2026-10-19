package com.depromeet.piki.notification.sse

import com.depromeet.piki.notification.controller.dto.NotificationSsePayload
import com.depromeet.piki.notification.controller.dto.SilentSyncPayload
import com.depromeet.piki.notification.domain.Notification
import com.depromeet.piki.notification.domain.NotificationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.UUID

// emitter write·complete 가 일어나는 유일한 자리. SseNotificationChannel.send 와 분리한 이유는 스케일아웃 seam 이다:
// 멀티 인스턴스가 되면 send() 는 Redis publish 로 바뀌고 각 인스턴스의 subscriber 가 이 deliver() 를 부른다.
@Component
class LocalSseDelivery(
    private val registry: SseEmitterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 인사 뒤에 등록한다 - 등록된 연결은 반드시 connect 를 보낸 연결이고, 실패 시 정리할 것이 없다.
    // 끊김은 컨테이너 onError 가 정상 완료로 끝내고, 그 뒤 Spring 은 예외 결과 dispatch 를 조용히 삼킨다.
    fun open(userId: UUID): SseEmitter {
        // 타임아웃 없음. 연결 수명은 양방향 하트비트(서버 ping 쓰기 실패 · 클라이언트 하트비트 결측)가 결정한다.
        val emitter = SseEmitter(NO_TIMEOUT)
        val connection = SseConnection(userId, emitter)
        emitter.onCompletion { registry.unregister(connection) }
        // 타임아웃은 서버 종료 때 Tomcat 이 남은 연결에 강제로만 건다. Spring 의 예외 결과 dispatch 가 ERROR 로그를 남기므로 정상 완료로 덮는다.
        emitter.onTimeout { emitter.complete() }
        // 컨트롤러 반환 전이라 소켓이 아닌 버퍼에 쌓이고, 실제 쓰기와 그 실패 처리는 Spring 의 initialize 가 맡는다.
        emitter.send(SseEmitter.event().name(EVENT_CONNECT).data(connection.id.toString()))
        registry.register(connection)
        return emitter
    }

    fun heartbeat(
        userId: UUID,
        connectionId: UUID,
    ) {
        if (!registry.touch(userId, connectionId, Instant.now())) throw NotificationException.unknownConnection()
    }

    // write 에 성공한 연결 수를 돌려준다. 자동읽음(#812)의 근거라 "연결이 있었나" 가 아니라 "실제로 썼나" 여야 한다.
    fun deliver(
        userId: UUID,
        notification: Notification,
    ): Int {
        val event =
            SseEmitter
                .event()
                .name(EVENT_NOTIFICATION)
                .data(NotificationSsePayload.from(notification))
        return broadcast(registry.connectionsOf(userId)) { event }
    }

    fun deliverSilentSync(
        userIds: Collection<UUID>,
        payload: SilentSyncPayload,
    ) {
        val event =
            SseEmitter
                .event()
                .name(EVENT_SILENT_SYNC)
                .data(payload)
        broadcast(userIds.flatMap { registry.connectionsOf(it) }) { event }
    }

    fun closeAll(userId: UUID) {
        registry.removeAll(userId).forEach { complete(it, "탈퇴") }
    }

    // 주석(`: ping`)은 표준 EventSource 에 노출되지 않아 이름 붙은 이벤트로 보낸다. data 없는 event 는 디스패치되지 않는다.
    fun ping() {
        broadcast(registry.all()) { SseEmitter.event().name(EVENT_HEARTBEAT).data(it.id.toString()) }
    }

    // 이 INFO 건수가 #1057 효과 판정 지표다.
    fun evictStale(
        now: Instant,
        threshold: Duration,
    ): Int {
        val stale = registry.removeStale(now, threshold)
        stale.forEach { connection ->
            log.info(
                "SSE 클라이언트 하트비트 결측으로 연결 종료 userId={} connectionId={} lastHeartbeatAt={}",
                connection.userId,
                connection.id,
                connection.lastHeartbeatAt,
            )
            complete(connection, "결측")
        }
        return stale.size
    }

    // 한 연결의 실패가 나머지 연결로 번지지 않게 연결 단위로 격리하고, 성공한 연결 수를 돌려준다.
    // 여기서 연결을 닫지 않는다 - 끊긴 연결(IOException)은 컨테이너가 onError 로 알려 오고, 여기서 complete 하면 Spring 의
    // 1회용 결과를 먼저 차지해 onError 쪽 complete 가 무력화된다(ResponseBodyEmitter.send·complete Javadoc). 그 외 실패는
    // payload 쪽 문제라 연결이 멀쩡하다.
    private fun broadcast(
        connections: Collection<SseConnection>,
        event: (SseConnection) -> SseEmitter.SseEventBuilder,
    ): Int =
        connections.count { connection ->
            try {
                send(connection, event(connection))
                true
            } catch (e: IOException) {
                log.debug("SSE write 실패(연결 끊김) userId={} connectionId={}", connection.userId, connection.id, e)
                false
            } catch (e: Exception) {
                log.warn("SSE write 실패 userId={}", connection.userId, e)
                false
            }
        }

    private fun send(
        connection: SseConnection,
        event: SseEmitter.SseEventBuilder,
    ) {
        connection.emitter.send(event)
    }

    // completeWithError 금지(#1024): 헤더가 나간 뒤의 에러 종료는 Tomcat 의 /error ERROR 디스패치를 부르고,
    // 그 디스패치엔 인증이 없어 AuthorizationDenied + "already committed" 서버 에러 두 줄이 된다.
    private fun complete(
        connection: SseConnection,
        reason: String,
    ) {
        runCatching { connection.emitter.complete() }
            .onFailure { e ->
                log.warn("SSE {} 종료 실패 userId={} connectionId={}", reason, connection.userId, connection.id, e)
            }
    }

    companion object {
        const val EVENT_CONNECT = "connect"
        const val NO_TIMEOUT = 0L
        const val EVENT_NOTIFICATION = "notification"
        const val EVENT_HEARTBEAT = "heartbeat"
        const val EVENT_SILENT_SYNC = "silent-sync"
    }
}
