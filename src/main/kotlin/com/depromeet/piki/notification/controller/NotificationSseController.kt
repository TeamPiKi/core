package com.depromeet.piki.notification.controller

import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.notification.controller.dto.ClientHeartbeatRequest
import com.depromeet.piki.notification.domain.NotificationException
import com.depromeet.piki.notification.sse.SseEmitterRegistry
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/notifications")
class NotificationSseController(
    private val registry: SseEmitterRegistry,
) : NotificationSseApi {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/subscribe", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    override fun subscribe(
        @AuthenticationPrincipal userId: UUID,
    ): SseEmitter {
        val emitter = SseEmitter(SSE_TIMEOUT_MS)
        val connection = registry.register(userId, emitter)
        // 에러·타임아웃 뒤 아무도 complete 하지 않으면 Tomcat 이 /error 로 ERROR 디스패치를 걸고, 거기엔 JWT 필터가 안 돌아
        // AuthorizationDenied + "already committed" 서버 에러 두 줄이 난다(#1029). 같은 이유로 completeWithError 는 쓰지 않는다(#1024).
        // 이 complete 는 sendOrEvict 가 먼저 결과를 설정한 뒤엔 무력화되므로(Spring 은 async 결과를 한 번만 받음) 경합을 완전히
        // 막지는 못한다. 그래서 서버가 하트비트 결측으로 먼저 닫는 경로(evictStale)를 둔다.
        val unregisterAndComplete = {
            registry.unregister(connection)
            emitter.complete()
        }
        emitter.onCompletion { registry.unregister(connection) }
        emitter.onError { unregisterAndComplete() }
        emitter.onTimeout { unregisterAndComplete() }
        // 헤더를 즉시 flush 하는 첫 이벤트. data 는 연결 번호다.
        runCatching {
            emitter.send(SseEmitter.event().name(EVENT_CONNECT).data(connection.id.toString()))
        }.onFailure { e ->
            log.warn("SSE 최초 connect 전송 실패 userId={}", userId, e)
            unregisterAndComplete()
        }
        return emitter
    }

    @PostMapping("/heartbeat")
    override fun heartbeat(
        @AuthenticationPrincipal userId: UUID,
        @Valid @RequestBody request: ClientHeartbeatRequest,
    ): ApiResponseBody<Unit> {
        if (!registry.touch(userId, request.connectionId, Instant.now())) throw NotificationException.unknownConnection()
        return ApiResponseBody.ok()
    }

    companion object {
        const val EVENT_CONNECT = "connect"
        const val SSE_TIMEOUT_MS = 30 * 60 * 1000L
    }
}
