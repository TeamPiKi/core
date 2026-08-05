package com.depromeet.piki.notification.repository

import com.depromeet.piki.notification.domain.Notification
import com.depromeet.piki.notification.domain.NotificationCursor
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

    // cursor 유무로 첫 페이지 / 다음 페이지가 갈린다.
    override fun findPage(
        userId: UUID,
        cursor: NotificationCursor?,
        limit: Int,
    ): List<Notification> {
        val limited = Limit.of(limit)
        cursor ?: return notificationJpaRepository.findByUserIdOrderByIdDesc(userId, limited)
        return notificationJpaRepository.findByUserIdAndIdLessThanOrderByIdDesc(userId, cursor.lastNotificationId, limited)
    }

    override fun countUnread(userId: UUID): Long = notificationJpaRepository.countUnread(userId)

    // 대상 유저 전원을 0 으로 깔고(안읽음 0 인 유저도 키 보장), IN + GROUP BY 한 쿼리 결과를 덮는다.
    // userIds 가 비면 쿼리를 건너뛴다(빈 IN 회피).
    override fun countUnreadForUsers(userIds: Collection<UUID>): Map<UUID, Long> {
        if (userIds.isEmpty()) return emptyMap()
        val result = userIds.associateWithTo(mutableMapOf()) { 0L }
        notificationJpaRepository.countUnreadForUsers(userIds).forEach { row ->
            result[row.userId] = row.count
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
