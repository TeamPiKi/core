package com.depromeet.piki.notification.controller.dto

import com.depromeet.piki.notification.domain.Notification
import io.swagger.v3.oas.annotations.media.Schema

// 알림 히스토리 목록 응답. items 는 SSE 와 같은 셰입(NotificationSsePayload)을 그대로 재사용해, 클라가
// SSE 로 받든 히스토리로 받든 동일한 객체로 다루게 한다(셰입 drift 방지 — 단일 소스). 페이징(nextCursor·hasNext)은
// ApiResponseBody.pageResponse 가 싣고, 여기엔 전체 안읽음 수(unreadCount, 앱 badge)를 동봉한다.
@Schema(description = "알림 히스토리 목록 + 안읽음 수")
data class NotificationHistoryResponse(
    @field:Schema(description = "알림 목록 (최신순). 각 항목 셰입은 SSE notification 이벤트 payload 와 동일")
    val items: List<NotificationSsePayload>,
    @field:Schema(description = "본인 전체 안읽음 알림 수 (앱 badge). 페이지 범위가 아니라 항상 전체", example = "3")
    val unreadCount: Long,
) {
    companion object {
        fun of(
            notifications: List<Notification>,
            unreadCount: Long,
        ): NotificationHistoryResponse =
            NotificationHistoryResponse(
                items = notifications.map { NotificationSsePayload.from(it) },
                unreadCount = unreadCount,
            )
    }
}
