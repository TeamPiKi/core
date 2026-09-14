package com.depromeet.piki.notification.controller

import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.notification.controller.dto.ClientHeartbeatRequest
import com.depromeet.piki.notification.domain.NotificationException
import com.depromeet.piki.notification.sse.LocalSseDelivery
import com.depromeet.piki.notification.sse.SseEmitterRegistry
import jakarta.validation.Valid
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
    private val localDelivery: LocalSseDelivery,
) : NotificationSseApi {
    @GetMapping("/subscribe", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    override fun subscribe(
        @AuthenticationPrincipal userId: UUID,
    ): SseEmitter = localDelivery.open(userId)

    @PostMapping("/heartbeat")
    override fun heartbeat(
        @AuthenticationPrincipal userId: UUID,
        @Valid @RequestBody request: ClientHeartbeatRequest,
    ): ApiResponseBody<Unit> {
        if (!registry.touch(userId, request.connectionId, Instant.now())) throw NotificationException.unknownConnection()
        return ApiResponseBody.ok()
    }
}
