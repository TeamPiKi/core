package com.depromeet.piki.notification.repository

import com.depromeet.piki.notification.domain.Notification
import com.depromeet.piki.notification.domain.NotificationCategory
import com.depromeet.piki.notification.domain.NotificationCursor
import com.depromeet.piki.notification.domain.NotificationType
import java.util.UUID

interface NotificationRepository {
    fun save(notification: Notification): Notification

    // 탈퇴 cascade — 그 수신자의 알림을 일괄 하드삭제하고 영향 건수를 돌려준다. 멱등(없으면 0건).
    fun hardDeleteAllByUserId(userId: UUID): Int

    // 본인 알림을 최신순(id desc)으로 최대 limit 건. cursor 가 있으면 그 id 미만만(다음 페이지).
    // types 가 있으면 그 type 집합만(카테고리 탭 필터), null 이면 전체.
    fun findPage(
        userId: UUID,
        cursor: NotificationCursor?,
        limit: Int,
        types: List<NotificationType>?,
    ): List<Notification>

    // SSE 재연결 replay — afterId(클라이언트가 마지막으로 받은 알림 id) 초과분을 발생 순서(id asc)로 최대 limit 건.
    fun findAfterId(
        userId: UUID,
        afterId: Long,
        limit: Int,
    ): List<Notification>

    // 카테고리별 안읽음 수(탭별 badge). 모든 카테고리를 키로 포함하며 해당 없는 카테고리는 0 이다.
    // 전체 안읽음 수(앱 badge)는 이 맵의 값 합으로 도출한다 — 두 수치가 어긋날 여지를 없앤다.
    fun countUnreadByCategory(userId: UUID): Map<NotificationCategory, Long>

    // 지정 id 중 본인 소유 + 안읽음만 read 로 (타인/없는 id 무영향). 영향 건수 반환. 멱등.
    fun markRead(
        userId: UUID,
        ids: List<Long>,
    ): Int

    // 본인 안읽음 전부 read. 영향 건수 반환. 멱등.
    fun markAllRead(userId: UUID): Int
}
