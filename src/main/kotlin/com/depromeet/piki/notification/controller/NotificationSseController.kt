package com.depromeet.piki.notification.controller

import com.depromeet.piki.notification.sse.SseEmitterRegistry
import com.depromeet.piki.notification.sse.SseReconnectReplayer
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID

@RestController
@RequestMapping("/api/v1/notifications")
class NotificationSseController(
    private val registry: SseEmitterRegistry,
    private val replayer: SseReconnectReplayer,
) : NotificationSseApi {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/subscribe", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    override fun subscribe(
        @AuthenticationPrincipal userId: UUID,
        @RequestHeader(HEADER_LAST_EVENT_ID, required = false) lastEventIdHeader: String?,
    ): SseEmitter {
        val emitter = SseEmitter(SSE_TIMEOUT_MS)
        // 연결 종료(정상 종료·에러·타임아웃) 시 레지스트리에서 제거해 죽은 emitter 누적을 막는다. unregister 는 멱등.
        emitter.onCompletion { registry.unregister(userId, emitter) }
        emitter.onError { registry.unregister(userId, emitter) }
        emitter.onTimeout {
            registry.unregister(userId, emitter)
            emitter.complete()
        }
        // register 를 replay 조회보다 먼저 둔다 — 반대 순서(조회 후 register)면 그 틈에 발행된 라이브 알림이
        // 유실된다. 이 순서로는 같은 알림이 라이브·replay 양쪽으로 겹칠 수 있으나(중복), 클라이언트가 id 로
        // dedup 하는 계약이다(notification-sse-spec.md). 유실보다 중복이 옳다.
        registry.register(userId, emitter)
        // 최초 connect 이벤트로 응답 헤더를 즉시 flush 해 클라이언트가 "연결됨" 을 곧장 인지하게 한다.
        runCatching {
            emitter.send(SseEmitter.event().name(EVENT_CONNECT).data("connected"))
        }.onFailure { e ->
            log.warn("SSE 최초 connect 전송 실패 userId={}", userId, e)
            registry.unregister(userId, emitter)
            emitter.completeWithError(e)
            return emitter
        }
        // 재연결이면(Last-Event-ID) 끊김 동안 놓친 알림을 이 연결에만 replay 한다.
        // 헤더 파싱은 보수적으로 — SSE 프로토콜상 서버는 id 재개를 지원하지 않을 수도 있는 optional 계약이라,
        // 숫자가 아니거나 양수가 아닌 값은 400 으로 끊지 않고 "첫 연결" 로 취급한다(재연결 루프를 깨지 않는다).
        lastEventIdHeader?.toLongOrNull()?.takeIf { it > 0 }?.let { replayer.replayMissed(userId, emitter, it) }
        return emitter
    }

    companion object {
        // connect 이벤트 name. 알림(notification)·하트비트(주석)와 구분된다.
        const val EVENT_CONNECT = "connect"

        // emitter 자체 타임아웃(30분). 만료되면 onTimeout 으로 정리되고 클라이언트가 재연결한다.
        const val SSE_TIMEOUT_MS = 30 * 60 * 1000L

        // SSE 표준 재연결 헤더 — EventSource 가 마지막 수신 이벤트 id 를 자동으로 싣는다. 값은 notification 의 알림 id.
        const val HEADER_LAST_EVENT_ID = "Last-Event-ID"
    }
}
