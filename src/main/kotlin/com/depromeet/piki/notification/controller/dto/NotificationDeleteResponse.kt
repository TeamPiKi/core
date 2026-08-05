package com.depromeet.piki.notification.controller.dto

import io.swagger.v3.oas.annotations.media.Schema

// 삭제 처리 응답 — 삭제 후 안읽음 수를 서버 권위 값으로 내려, 클라가 +1/-1 산수 없이 그대로 미러링하게 한다.
// 읽음 응답(NotificationReadResponse)과 같은 셰입이라, 삭제로 안읽음이 사라지면 앱 badge 를 한 왕복에서
// 갱신한다(다른 기기는 재진입 시 GET /notifications 로 보정).
@Schema(description = "알림 삭제 처리 응답 — 삭제 후 안읽음 수")
data class NotificationDeleteResponse(
    @field:Schema(description = "삭제 후 본인 전체 안읽음 수 (앱 badge). 클라는 이 값을 그대로 badge 로 미러링한다", example = "1")
    val unreadCount: Long,
) {
    companion object {
        fun of(unreadCount: Long): NotificationDeleteResponse = NotificationDeleteResponse(unreadCount = unreadCount)
    }
}
