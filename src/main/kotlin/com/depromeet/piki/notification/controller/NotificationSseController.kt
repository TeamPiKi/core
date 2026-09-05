package com.depromeet.piki.notification.controller

import com.depromeet.piki.notification.sse.SseEmitterRegistry
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
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
        // 연결 종료(정상 종료·에러·타임아웃) 시 레지스트리에서 제거해 죽은 emitter 누적을 막는다. unregister 는 멱등.
        //
        // 에러·타임아웃 콜백은 정리에 더해 complete() 로 요청을 끝맺어야 한다. 서블릿 규격상 컨테이너가
        // AsyncListener 를 부른 뒤 아무도 complete·dispatch 를 하지 않으면 컨테이너가 /error 로 ERROR 디스패치를
        // 걸고, 그 디스패치엔 JWT 필터가 돌지 않아(OncePerRequestFilter) 인가가 비어 Access Denied 가 난다.
        // SSE 응답은 헤더가 이미 나가 committed 라 그 401 조차 쓰지 못해 서버 에러 로그 두 줄로 끝난다(#1029).
        // Spring 도 이 자리를 애플리케이션에 맡긴다 - StandardServletAsyncWebRequest.onError 는 등록된 콜백만
        // 부르고 스스로 dispatch 하지 않는다.
        emitter.onCompletion { registry.unregister(userId, emitter) }
        emitter.onError {
            registry.unregister(userId, emitter)
            emitter.complete()
        }
        emitter.onTimeout {
            registry.unregister(userId, emitter)
            emitter.complete()
        }
        registry.register(userId, emitter)
        // 최초 connect 이벤트로 응답 헤더를 즉시 flush 해 클라이언트가 "연결됨" 을 곧장 인지하게 한다.
        runCatching {
            emitter.send(SseEmitter.event().name(EVENT_CONNECT).data("connected"))
        }.onFailure { e ->
            log.warn("SSE 최초 connect 전송 실패 userId={}", userId, e)
            registry.unregister(userId, emitter)
            emitter.completeWithError(e)
        }
        return emitter
    }

    companion object {
        // connect 이벤트 name. 알림(notification)·하트비트(주석)와 구분된다.
        const val EVENT_CONNECT = "connect"

        // emitter 자체 타임아웃(30분). 만료되면 onTimeout 으로 정리되고 클라이언트가 재연결한다.
        const val SSE_TIMEOUT_MS = 30 * 60 * 1000L
    }
}
