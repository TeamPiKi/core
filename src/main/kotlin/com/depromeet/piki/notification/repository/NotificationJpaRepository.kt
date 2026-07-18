package com.depromeet.piki.notification.repository

import com.depromeet.piki.notification.domain.Notification
import com.depromeet.piki.notification.domain.NotificationType
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime
import java.util.UUID

interface NotificationJpaRepository : JpaRepository<Notification, Long> {
    // 탈퇴 cascade + 모두 삭제(NotificationDeleteCommand.All) — 그 수신자(userId) 의 알림을 일괄 하드삭제. 멱등(없으면 0건).
    // 벌크 DELETE 는 1차 캐시를 우회하므로, 삭제 전 pending 을 반영(flush)하고 삭제 후 stale 관리 엔티티를 비운다(clear) —
    // deleteByUserIdAndIds·markRead 와 동일. 이로써 같은 트랜잭션의 이후 조회가 삭제 결과와 어긋나지 않는다.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM Notification n WHERE n.userId = :userId")
    fun hardDeleteAllByUserId(
        @Param("userId") userId: UUID,
    ): Int

    // 히스토리 목록 첫 페이지 — 본인 알림을 최신순(id desc)으로 limit 건. (idx_notifications_user_id_is_read 의 user_id leftmost)
    fun findByUserIdOrderByIdDesc(
        userId: UUID,
        limit: Limit,
    ): List<Notification>

    // 히스토리 목록 다음 페이지 — 커서(직전 페이지 마지막 id) 미만만 최신순 limit 건.
    fun findByUserIdAndIdLessThanOrderByIdDesc(
        userId: UUID,
        id: Long,
        limit: Limit,
    ): List<Notification>

    // 카테고리 탭 첫 페이지 — 본인 알림 중 그 카테고리 type 집합만 최신순 limit 건. (idx (user_id, id) 가 정렬을 받치고 type 은 필터)
    fun findByUserIdAndTypeInOrderByIdDesc(
        userId: UUID,
        types: List<NotificationType>,
        limit: Limit,
    ): List<Notification>

    // 카테고리 탭 다음 페이지 — 커서 미만 + 그 카테고리 type 집합만 최신순 limit 건.
    fun findByUserIdAndIdLessThanAndTypeInOrderByIdDesc(
        userId: UUID,
        id: Long,
        types: List<NotificationType>,
        limit: Limit,
    ): List<Notification>

    // type 별 안읽음 수 — 전체 badge + 탭별(활동/시스템) badge 를 한 쿼리(group by)로 집계한다.
    // closed projection(type·count alias)으로 받아 RepositoryImpl 이 카테고리로 접는다. (idx (user_id, is_read) 커버)
    @Query(
        "SELECT n.type AS type, COUNT(n) AS count FROM Notification n " +
            "WHERE n.userId = :userId AND n.isRead = false GROUP BY n.type",
    )
    fun countUnreadByType(
        @Param("userId") userId: UUID,
    ): List<TypeUnreadCount>

    // group by 결과 한 행 — type 과 그 안읽음 수. Spring Data closed projection(SELECT alias 와 게터명 일치).
    interface TypeUnreadCount {
        val type: NotificationType
        val count: Long
    }

    // 여러 유저의 안읽음 수를 (userId, type) 별로 한 쿼리에 집계 — 자동삭제 배지 재계산의 N+1 을 없앤다(유저마다 쿼리 대신 IN + GROUP BY 한 번).
    // 안읽음이 0 인 유저는 결과 행에 안 나오므로, 호출부가 affectedUsers 전체를 0 으로 깔고 이 결과를 덮어 계약(모든 대상 유저 키 포함)을 유지한다.
    @Query(
        "SELECT n.userId AS userId, n.type AS type, COUNT(n) AS count FROM Notification n " +
            "WHERE n.userId IN :userIds AND n.isRead = false GROUP BY n.userId, n.type",
    )
    fun countUnreadByUserAndType(
        @Param("userIds") userIds: Collection<UUID>,
    ): List<UserTypeUnreadCount>

    // group by 결과 한 행 — userId·type 과 그 안읽음 수. Spring Data closed projection(SELECT alias 와 게터명 일치).
    interface UserTypeUnreadCount {
        val userId: UUID
        val type: NotificationType
        val count: Long
    }

    // 본인 소유(user_id) + 지정 id + 아직 안읽음만 read 로. user_id 가 WHERE 에 있어 타인/없는 id 는 무영향(소유 검증 겸용).
    // 영향 건수를 돌려준다. 멱등(이미 읽음·없는 id 는 0건).
    // 벌크 UPDATE 는 1차 캐시를 우회하므로, 같은 트랜잭션에서 이후 재조회가 stale 값을 읽지 않도록 컨텍스트를 flush·clear 한다.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Notification n SET n.isRead = true WHERE n.userId = :userId AND n.id IN :ids AND n.isRead = false")
    fun markReadByUserIdAndIds(
        @Param("userId") userId: UUID,
        @Param("ids") ids: Collection<Long>,
    ): Int

    // 본인 안읽음 전부 read. 영향 건수를 돌려준다. 멱등.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Notification n SET n.isRead = true WHERE n.userId = :userId AND n.isRead = false")
    fun markAllReadByUserId(
        @Param("userId") userId: UUID,
    ): Int

    // 본인 소유(user_id) + 지정 id 만 하드삭제 (읽음 무관). user_id 가 WHERE 에 있어 타인/없는 id 는 무영향(소유 검증 겸용).
    // 영향 건수를 돌려준다. 멱등(없는 id 는 0건). 벌크 DELETE 는 1차 캐시를 우회하므로 같은 트랜잭션의 이후
    // 안읽음 재집계가 stale 값을 읽지 않도록 컨텍스트를 flush·clear 한다(markReadByUserIdAndIds 와 동일 이유).
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM Notification n WHERE n.userId = :userId AND n.id IN :ids")
    fun deleteByUserIdAndIds(
        @Param("userId") userId: UUID,
        @Param("ids") ids: Collection<Long>,
    ): Int

    // N일 자동삭제로 배지가 바뀔 유저 — cutoff 미만 + 안읽음 알림을 가진 유저의 id 를 중복 없이. 삭제 전에 모아 둔다(삭제 후엔 사라진다).
    // 읽음만 지워지는 유저는 unread 가 안 변해 배지 동기화가 불필요하므로 제외한다(is_read=false 조건). (idx (user_id, is_read) 커버)
    @Query("SELECT DISTINCT n.userId FROM Notification n WHERE n.createdAt < :cutoff AND n.isRead = false")
    fun findUserIdsWithUnreadCreatedBefore(
        @Param("cutoff") cutoff: LocalDateTime,
    ): List<UUID>

    // N일 자동삭제 — created_at 이 cutoff 미만인 알림을 유저 무관 전부 하드삭제(읽음 무관). 영향 건수 반환. 멱등.
    // age 기준이라 blue-green 중복 실행에도 결과가 같다(commutative). (idx_notifications_created_at 이 풀스캔을 막는다)
    // 벌크 DELETE 는 1차 캐시를 우회하므로, 삭제 전 pending insert 를 반영(flush)하고 삭제 후 stale 관리 엔티티를 비운다(clear).
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM Notification n WHERE n.createdAt < :cutoff")
    fun deleteByCreatedAtBefore(
        @Param("cutoff") cutoff: LocalDateTime,
    ): Int
}
