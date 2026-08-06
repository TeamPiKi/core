package com.depromeet.piki.notification.controller.dto

import io.swagger.v3.oas.annotations.media.Schema

// 읽음 처리 응답 — 처리 후 안읽음 수를 서버 권위 값으로 내려, 클라가 +1/-1 산수 없이 그대로 미러링하게 한다.
// 멀티 디바이스에서도 읽은 기기는 추가 조회 없이 최신 badge 를 같은 왕복에서 받는다(다른 기기는 재진입 시 GET /notifications 로 보정).
@Schema(description = "알림 읽음 처리 응답 — 처리 후 안읽음 수")
data class NotificationReadResponse(
    @field:Schema(description = "처리 후 본인 전체 안읽음 수 (앱 badge). 클라는 이 값을 그대로 badge 로 미러링한다", example = "2")
    val unreadCount: Long,
) {
    companion object {
        fun of(unreadCount: Long): NotificationReadResponse = NotificationReadResponse(unreadCount = unreadCount)
    }
}
