package com.depromeet.piki.notification.sse

import com.depromeet.piki.notification.controller.dto.NotificationSsePayload
import com.depromeet.piki.notification.controller.dto.SilentSyncPayload
import com.depromeet.piki.notification.domain.Notification
import com.depromeet.piki.notification.service.DefaultPushImage
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.util.UUID

// SSE 의 "로컬 write" 지점 — 레지스트리에 든 emitter 들에 실제로 이벤트를 흘려보내고, write 가 실패하는
// 죽은 연결을 정리한다. emitter write 가 일어나는 곳은 여기 한 곳으로 모은다(전달·replay·하트비트 공용).
//
// 외부 진입(SseNotificationChannel.send)과 분리한 이유는 스케일아웃 seam 이다: 다중
// 인스턴스가 되면 send() 는 Redis publish 로 바뀌고, 각 인스턴스의 Redis subscriber 가 이 deliver() 를
// 호출(= 모든 인스턴스가 자기 로컬 연결에만 write)한다. 그 전환에서 이 클래스와 레지스트리는 그대로 산다.
// (그때 이벤트 로그 적재는 발행 측으로 옮겨 인스턴스 수만큼 중복 적재되지 않게 한다.)
//
// payload 는 여기서 JSON 문자열로 한 번 직렬화해 live 전송과 이벤트 로그가 같은 바이트를 공유한다 —
// replay(SseEventRecord 재전송)가 live 와 동일한 와이어를 재현하는 근거다.
@Component
class LocalSseDelivery(
    private val registry: SseEmitterRegistry,
    private val defaultPushImage: DefaultPushImage,
    private val sseEventLog: SseEventLog,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 한 유저의 모든 연결에 알림 이벤트를 보낸다. 이벤트는 유저당 한 번 만들어 그 유저의 emitter 들에 재사용한다.
    fun deliver(
        userId: UUID,
        notification: Notification,
    ) {
        val payloadJson =
            objectMapper.writeValueAsString(NotificationSsePayload.from(notification, defaultPushImage.url))
        val event = loggedEvent(userId, EVENT_NOTIFICATION, payloadJson)
        registry.emittersOf(userId).forEach { sendOrEvict(userId, it, event) }
    }

    // 조용한(silent) 화면 갱신 신호를 대상 유저들 연결에 실시간 흘려보낸다(알림 아님 — 토스트·알림센터·FCM 표시 푸시 없이 SSE 로만).
    // notification 전달과 같은 emitter write 경로(sendOrEvict)를 공유하되 이벤트 name 만 다르다(클라가 name 으로 구분).
    // payload JSON 은 한 번 직렬화해 재사용하되, 이벤트 id 는 유저별 로그가 발급하므로 이벤트는 유저 단위로 만든다.
    // 동기 로컬 write 다. broadcaster 는 이미 @Async 워커에서 호출하므로 직접 부르고, 읽음 응답 경로(badge)는
    // SilentSyncDispatcher 가 @Async 로 감싸 요청 스레드(emitter write·throw)가 읽음 응답을 막지 않게 한다.
    fun deliverSilentSync(
        userIds: Collection<UUID>,
        payload: SilentSyncPayload,
    ) {
        val payloadJson = objectMapper.writeValueAsString(payload)
        userIds.forEach { userId ->
            val event = loggedEvent(userId, EVENT_SILENT_SYNC, payloadJson)
            registry.emittersOf(userId).forEach { sendOrEvict(userId, it, event) }
        }
    }

    // 재연결한 특정 연결 하나에만 로그의 이벤트들을 원본 그대로(id·name·payload) 다시 흘려보낸다.
    // write 실패 = 그 연결이 죽은 것이므로 sendOrEvict 가 정리하고, 나머지 replay 는 의미가 없어 중단한다.
    fun replayTo(
        userId: UUID,
        emitter: SseEmitter,
        records: List<SseEventRecord>,
    ) {
        for (record in records) {
            val event =
                SseEmitter
                    .event()
                    .id(record.id)
                    .name(record.eventName)
                    .data(record.payloadJson, MediaType.APPLICATION_JSON)
            if (!sendOrEvict(userId, emitter, event)) return
        }
    }

    // 데이터 이벤트 한 건 — 이벤트 로그에 적재하고 발급된 id 를 SSE id 필드에 싣는다. 클라이언트(EventSource)는
    // 마지막 수신 id 를 기억했다가 재연결 시 Last-Event-ID 로 보내 놓친 구간을 replay 받는다.
    // 적재는 연결 유무와 무관하다(emittersOf 가 비어도 로그에는 남아 이후 재연결에서 복구된다).
    // 적재 실패 시 id 없이 live 전송만 한다 — id 없는 이벤트는 클라 lastEventId 를 갱신하지 않아
    // 복구 기준점이 마지막 "적재된" 이벤트로 유지된다(connect·하트비트가 id 를 안 싣는 것과 같은 원리).
    private fun loggedEvent(
        userId: UUID,
        eventName: String,
        payloadJson: String,
    ): SseEmitter.SseEventBuilder {
        val event = SseEmitter.event().name(eventName)
        sseEventLog.append(userId, eventName, payloadJson)?.let { event.id(it) }
        return event.data(payloadJson, MediaType.APPLICATION_JSON)
    }

    // 탈퇴 시 그 유저의 모든 SSE 연결을 즉시 끊는다(best-effort). 레지스트리에서 키째 빼고 각 emitter 를
    // complete 한다. complete 가 컨트롤러의 onCompletion(unregister)을 다시 깨워도 unregister 는 멱등이라 무해.
    // 인스턴스-로컬 연결만 끊는다 — 멀티 인스턴스로 확장돼도 각 인스턴스가 자기 메모리 연결만 정리하면 된다.
    fun closeAll(userId: UUID) {
        registry.removeAll(userId).forEach { emitter ->
            runCatching { emitter.complete() }
                .onFailure { e -> log.warn("SSE 연결 종료 실패 userId={}", userId, e) }
        }
    }

    // 전 연결에 주석 ping 을 보내 연결을 살아있게 유지하고, 끊긴 연결을 정리한다. (스케줄러가 주기 호출)
    fun heartbeat() {
        val ping = SseEmitter.event().comment("ping")
        registry.forEach { userId, emitter -> sendOrEvict(userId, emitter, ping) }
    }

    // write 실패 = 죽은 연결(클라이언트가 끊겼는데 onError/onCompletion 콜백이 아직 안 탄 경우 등).
    // 레지스트리에서 빼 더 흘려보내지 않게 하고 completeWithError 로 정리를 마무리한다. completeWithError 가
    // onError 콜백(컨트롤러의 unregister)을 다시 깨울 수 있으나 unregister 는 멱등이라 중복 호출이 무해하다.
    // 성공 여부를 돌려줘 한 연결에 연속 write 하는 replay 가 죽은 연결에 헛 write 를 반복하지 않게 한다.
    private fun sendOrEvict(
        userId: UUID,
        emitter: SseEmitter,
        event: SseEmitter.SseEventBuilder,
    ): Boolean =
        runCatching { emitter.send(event) }
            .onFailure { e ->
                // 클라이언트가 연결을 끊은 정상 종료는 SSE 의 일상적 라이프사이클이라 DEBUG 로 둔다.
                // Broken pipe·ClientAbortException·AsyncRequestNotUsableException 은 모두 IOException 하위이므로
                // is IOException 한 분기로 묶인다. 그 외(예: 이미 complete 된 emitter 에 write 한 IllegalStateException)만
                // 진짜 이상 신호로 WARN 한다. (정리 동작 자체는 두 경우 모두 동일하다.)
                when (e) {
                    is IOException -> log.debug("SSE write 실패(연결 끊김)로 emitter 정리 userId={}", userId, e)
                    else -> log.warn("SSE write 실패로 emitter 정리 userId={}", userId, e)
                }
                registry.unregister(userId, emitter)
                runCatching { emitter.completeWithError(e) }
            }.isSuccess

    companion object {
        // SSE 이벤트 name. 클라이언트는 이 이름으로 알림 이벤트와 connect/하트비트를 구분한다.
        const val EVENT_NOTIFICATION = "notification"

        // 조용한(silent) 화면 갱신 신호의 SSE 이벤트 name. notification(보이는 알림)과 구분되며, 알림이 아니라
        // 라이브 동기화라 알림센터·FCM 표시 푸시를 거치지 않는다(SilentSyncPayload). "silent" 는 토스트가 뜨는
        // 기존 알림과 달리 조용히 화면만 갱신함을 명시한다(분류 용어로 쓰이던 sync·FCM data 의 badge_sync 와도 구별).
        const val EVENT_SILENT_SYNC = "silent-sync"
    }
}
