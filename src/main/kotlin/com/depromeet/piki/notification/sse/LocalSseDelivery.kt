package com.depromeet.piki.notification.sse

import com.depromeet.piki.notification.controller.dto.NotificationSsePayload
import com.depromeet.piki.notification.controller.dto.SilentSyncPayload
import com.depromeet.piki.notification.domain.Notification
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
        return registry.connectionsOf(userId).count { sendOrEvict(it, event) }
    }

    // 알림이 아닌 화면 갱신 신호. 알림센터·FCM 을 거치지 않고 SSE 로만 흐른다(SilentSyncPayload).
    fun deliverSilentSync(
        userIds: Collection<UUID>,
        payload: SilentSyncPayload,
    ) {
        val event =
            SseEmitter
                .event()
                .name(EVENT_SILENT_SYNC)
                .data(payload)
        userIds.forEach { userId -> registry.connectionsOf(userId).forEach { sendOrEvict(it, event) } }
    }

    fun closeAll(userId: UUID) {
        registry.removeAll(userId).forEach { complete(it, "탈퇴") }
    }

    // 주석(`: ping`)은 표준 EventSource 에 노출되지 않아 이름 붙은 이벤트로 보낸다. data 없는 event 는 디스패치되지 않는다.
    fun ping() {
        registry.forEach { connection ->
            sendOrEvict(connection, SseEmitter.event().name(EVENT_HEARTBEAT).data(connection.id.toString()))
        }
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

    private fun sendOrEvict(
        connection: SseConnection,
        event: SseEmitter.SseEventBuilder,
    ): Boolean =
        runCatching { connection.emitter.send(event) }
            .onFailure { e ->
                // 끊긴 클라이언트로의 write 실패(IOException 계열)는 일상이라 DEBUG. 그 외는 이상 신호.
                when (e) {
                    is IOException -> log.debug("SSE write 실패(연결 끊김)로 연결 정리 userId={}", connection.userId, e)
                    else -> log.warn("SSE write 실패로 연결 정리 userId={}", connection.userId, e)
                }
                registry.unregister(connection)
                complete(connection, "write 실패")
            }.isSuccess

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
        const val EVENT_NOTIFICATION = "notification"
        const val EVENT_HEARTBEAT = "heartbeat"
        const val EVENT_SILENT_SYNC = "silent-sync"
    }
}
