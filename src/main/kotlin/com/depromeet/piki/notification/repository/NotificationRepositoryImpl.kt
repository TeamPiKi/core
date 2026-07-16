package com.depromeet.piki.notification.repository

import com.depromeet.piki.notification.domain.Notification
import com.depromeet.piki.notification.domain.NotificationCategory
import com.depromeet.piki.notification.domain.NotificationCursor
import com.depromeet.piki.notification.domain.NotificationType
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.util.UUID

@Repository
class NotificationRepositoryImpl(
    private val notificationJpaRepository: NotificationJpaRepository,
) : NotificationRepository {
    override fun save(notification: Notification): Notification = notificationJpaRepository.save(notification)

    override fun hardDeleteAllByUserId(userId: UUID): Int = notificationJpaRepository.hardDeleteAllByUserId(userId)

    // cursor(다음 페이지) × types(카테고리 필터) 분기. types 가 없으면 전체, 있으면 type-in 변형. 각 분기는 cursor 유무로 다시 갈린다.
    override fun findPage(
        userId: UUID,
        cursor: NotificationCursor?,
        limit: Int,
        types: List<NotificationType>?,
    ): List<Notification> {
        val limited = Limit.of(limit)
        types ?: return findPageAllTypes(userId, cursor, limited)
        return findPageInTypes(userId, cursor, types, limited)
    }

    private fun findPageAllTypes(
        userId: UUID,
        cursor: NotificationCursor?,
        limited: Limit,
    ): List<Notification> {
        cursor ?: return notificationJpaRepository.findByUserIdOrderByIdDesc(userId, limited)
        return notificationJpaRepository.findByUserIdAndIdLessThanOrderByIdDesc(userId, cursor.lastNotificationId, limited)
    }

    private fun findPageInTypes(
        userId: UUID,
        cursor: NotificationCursor?,
        types: List<NotificationType>,
        limited: Limit,
    ): List<Notification> {
        cursor ?: return notificationJpaRepository.findByUserIdAndTypeInOrderByIdDesc(userId, types, limited)
        return notificationJpaRepository.findByUserIdAndIdLessThanAndTypeInOrderByIdDesc(userId, cursor.lastNotificationId, types, limited)
    }

    // type 별 group-by 결과를 카테고리로 접는다. 모든 카테고리를 0 으로 깔고 해당 type 의 수를 카테고리에 누적해,
    // 안읽음이 없는 카테고리도 키로 0 을 보장한다(FE 가 탭 badge 를 항상 읽을 수 있게).
    override fun countUnreadByCategory(userId: UUID): Map<NotificationCategory, Long> {
        val result = NotificationCategory.entries.associateWithTo(mutableMapOf()) { 0L }
        notificationJpaRepository.countUnreadByType(userId).forEach { row ->
            result.merge(NotificationCategory.of(row.type), row.count, Long::plus)
        }
        return result
    }

    // 대상 유저 전원을 모든 카테고리 0 으로 깔고(안읽음 0 인 유저도 키 보장), IN + GROUP BY 한 쿼리 결과를 덮어 접는다.
    // userIds 가 비면 쿼리를 건너뛴다(빈 IN 회피).
    override fun countUnreadByCategoryForUsers(userIds: List<UUID>): Map<UUID, Map<NotificationCategory, Long>> {
        if (userIds.isEmpty()) return emptyMap()
        val result =
            userIds.associateWithTo(mutableMapOf<UUID, MutableMap<NotificationCategory, Long>>()) {
                NotificationCategory.entries.associateWithTo(mutableMapOf()) { 0L }
            }
        notificationJpaRepository.countUnreadByUserAndType(userIds).forEach { row ->
            result.getValue(row.userId).merge(NotificationCategory.of(row.type), row.count, Long::plus)
        }
        return result
    }

    override fun markRead(
        userId: UUID,
        ids: List<Long>,
    ): Int = notificationJpaRepository.markReadByUserIdAndIds(userId, ids)

    override fun markAllRead(userId: UUID): Int = notificationJpaRepository.markAllReadByUserId(userId)

    override fun deleteByUserIdAndIds(
        userId: UUID,
        ids: List<Long>,
    ): Int = notificationJpaRepository.deleteByUserIdAndIds(userId, ids)

    override fun findUserIdsWithUnreadCreatedBefore(cutoff: LocalDateTime): List<UUID> =
        notificationJpaRepository.findUserIdsWithUnreadCreatedBefore(cutoff)

    override fun deleteByCreatedAtBefore(cutoff: LocalDateTime): Int = notificationJpaRepository.deleteByCreatedAtBefore(cutoff)
}
