package com.depromeet.piki.notification.controller.dto

import com.depromeet.piki.notification.domain.NotificationCategory
import com.depromeet.piki.notification.service.toUnreadTotal
import io.swagger.v3.oas.annotations.media.Schema

// 삭제 처리 응답 — 삭제 후 안읽음 수(전체·탭별)를 서버 권위 값으로 내려, 클라가 +1/-1 산수 없이 그대로 미러링하게 한다.
// 읽음 응답(NotificationReadResponse)과 같은 셰입(unreadCount + unreadCountByCategory)이라, 삭제로 안읽음이 사라지면
// 앱 badge 와 활동/시스템 탭 badge 를 한 왕복에서 갱신한다(다른 기기는 재진입 시 GET /notifications 로 보정).
@Schema(description = "알림 삭제 처리 응답 — 삭제 후 안읽음 수(전체·탭별)")
data class NotificationDeleteResponse(
    @field:Schema(description = "삭제 후 본인 전체 안읽음 수 (앱 badge). 클라는 이 값을 그대로 badge 로 미러링한다", example = "1")
    val unreadCount: Long,
    @field:Schema(description = "삭제 후 카테고리별 안읽음 수 (탭 badge). 모든 카테고리 키 포함, 없으면 0", example = "{\"ACTIVITY\":1,\"SYSTEM\":0}")
    val unreadCountByCategory: Map<NotificationCategory, Long>,
) {
    companion object {
        // total 은 카테고리 합으로 도출 — toUnreadTotal 단일 소스를 거쳐 전체·탭별 두 수치가 어긋날 여지를 없앤다(read 와 동일 규칙).
        fun of(unreadCountByCategory: Map<NotificationCategory, Long>): NotificationDeleteResponse =
            NotificationDeleteResponse(
                unreadCount = unreadCountByCategory.toUnreadTotal(),
                unreadCountByCategory = unreadCountByCategory,
            )
    }
}
