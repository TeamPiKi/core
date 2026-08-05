package com.depromeet.piki.notification.controller

import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.common.response.PageResponse
import com.depromeet.piki.notification.controller.dto.NotificationDeleteRequest
import com.depromeet.piki.notification.controller.dto.NotificationDeleteResponse
import com.depromeet.piki.notification.controller.dto.NotificationHistoryResponse
import com.depromeet.piki.notification.controller.dto.NotificationReadRequest
import com.depromeet.piki.notification.controller.dto.NotificationReadResponse
import com.depromeet.piki.notification.service.NotificationDeleteOrchestrator
import com.depromeet.piki.notification.service.NotificationReadOrchestrator
import com.depromeet.piki.notification.service.NotificationService
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/notifications")
class NotificationHistoryController(
    private val notificationService: NotificationService,
    private val notificationReadOrchestrator: NotificationReadOrchestrator,
    private val notificationDeleteOrchestrator: NotificationDeleteOrchestrator,
) : NotificationHistoryApi {
    @GetMapping
    override fun getHistory(
        @AuthenticationPrincipal userId: UUID,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) size: Int?,
    ): ApiResponseBody<NotificationHistoryResponse> {
        val page = notificationService.getHistory(userId = userId, rawCursor = cursor, rawSize = size)
        return ApiResponseBody.ok(
            data = NotificationHistoryResponse.of(page.notifications, page.unreadCount),
            pageResponse = PageResponse(nextCursor = page.nextCursor, hasNext = page.hasNext),
        )
    }

    @PostMapping("/read")
    override fun read(
        @AuthenticationPrincipal userId: UUID,
        @Valid @RequestBody request: NotificationReadRequest,
    ): ApiResponseBody<NotificationReadResponse> {
        val unreadCount = notificationReadOrchestrator.readAndSyncBadge(userId = userId, command = request.toCommand())
        return ApiResponseBody.ok(data = NotificationReadResponse.of(unreadCount))
    }

    @DeleteMapping
    override fun delete(
        @AuthenticationPrincipal userId: UUID,
        @Valid @RequestBody request: NotificationDeleteRequest,
    ): ApiResponseBody<NotificationDeleteResponse> {
        val unreadCount = notificationDeleteOrchestrator.deleteAndSyncBadge(userId = userId, command = request.toCommand())
        return ApiResponseBody.ok(data = NotificationDeleteResponse.of(unreadCount))
    }
}
