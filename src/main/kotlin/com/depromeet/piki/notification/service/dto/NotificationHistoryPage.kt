package com.depromeet.piki.notification.service.dto

import com.depromeet.piki.notification.domain.Notification

// 알림 히스토리 cursor 페이지네이션 한 페이지의 결과.
// notifications 는 응답 매핑 전 도메인 엔티티, unreadCount 는 전체 안읽음 수(앱 badge).
// nextCursor 는 다음 요청에 그대로 돌려보낼 커서(마지막 항목 id 문자열), 마지막 페이지면 null.
// defaultPushImageUrl 은 시스템 알림(actor 없음)의 imageUrl 을 채우는 기본 아바타 — 서비스가 채워, 컨트롤러는 매핑에 그대로 넘긴다.
data class NotificationHistoryPage(
    val notifications: List<Notification>,
    val unreadCount: Long,
    val nextCursor: String?,
    val hasNext: Boolean,
    val defaultPushImageUrl: String,
)
