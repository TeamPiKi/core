package com.depromeet.piki.notification.service

import com.depromeet.piki.notification.domain.Notification
import com.depromeet.piki.notification.domain.NotificationType
import com.depromeet.piki.notification.repository.NotificationJpaRepository
import com.depromeet.piki.notification.repository.NotificationRepository
import com.depromeet.piki.support.IntegrationTestSupport
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// N일 자동삭제의 cutoff 경계 검증. 스케줄러(@Scheduled)가 아니라 순수 로직(purgeOlderThan)을 cutoff 을 직접 넘겨 결정적으로 검증한다.
// 전역 DELETE 라 다른 테스트가 커밋한 행까지 지울 수 있으나, 클래스 레벨 @Transactional 자동 롤백이 그 행을 복원하므로 안전하다.
// 삭제 여부는 findById(PK)가 아니라 존재 조회(전역 DELETE 의 clearAutomatically 로 컨텍스트가 비워져 DB 를 친다)로 확인한다.
@Transactional
class NotificationRetentionIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var notificationRetentionService: NotificationRetentionService

    @Autowired private lateinit var notificationRepository: NotificationRepository

    @Autowired private lateinit var notificationJpaRepository: NotificationJpaRepository

    @Autowired private lateinit var entityManager: EntityManager

    private fun seed(userId: UUID): Long =
        notificationRepository
            .save(Notification(userId, NotificationType.ITEM_PARSING_COMPLETED, "제목", "본문", 11L))
            .getId()

    // created_at 은 @CreatedDate·updatable=false 라 엔티티로는 못 바꾼다. 경계 테스트를 위해 native SQL 로 정확한 시각을 박는다.
    private fun setCreatedAt(
        id: Long,
        at: LocalDateTime,
    ) {
        entityManager.flush() // seed insert 를 DB 에 반영한 뒤 UPDATE 가 그 행을 친다
        entityManager
            .createNativeQuery("UPDATE notifications SET created_at = :at WHERE id = :id")
            .setParameter("at", at)
            .setParameter("id", id)
            .executeUpdate()
    }

    private fun exists(id: Long): Boolean = notificationJpaRepository.findById(id).isPresent

    @Test
    fun `cutoff 이후에 생성된 알림은 유저 무관 하드삭제된다`() {
        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()
        val a1 = seed(userA)
        val a2 = seed(userA)
        val b1 = seed(userB)

        // 방금 생성돼 created_at ≈ now < cutoff(now+1분) → 세 건 모두 대상.
        val cutoff = LocalDateTime.now().plusMinutes(1)
        notificationRetentionService.purgeOlderThan(cutoff)

        assertFalse(exists(a1))
        assertFalse(exists(a2))
        assertFalse(exists(b1)) // age 기준이라 유저 무관 전역 삭제
    }

    @Test
    fun `삭제로 안읽음이 사라진 유저만 배지 재계산 대상에 담고 읽음만 지워진 유저는 제외한다`() {
        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()
        val unreadOldA = seed(userA) // 안읽음·오래됨 → 삭제 대상, userA 배지 영향
        val unreadRecentA = seed(userA) // 안읽음·최근 → 남아 userA 의 삭제 후 안읽음 1건
        val readOldB = seed(userB) // 오래됨이나 읽음 → 삭제돼도 배지 불변이라 userB 는 대상 아님
        notificationRepository.markRead(userB, listOf(readOldB))
        val old = LocalDateTime.of(2020, 1, 1, 0, 0, 0)
        setCreatedAt(unreadOldA, old)
        setCreatedAt(readOldB, old)

        val cutoff = LocalDateTime.of(2021, 1, 1, 0, 0, 0) // old < cutoff < now(최근 건)
        val result = notificationRetentionService.purgeOlderThan(cutoff)

        assertFalse(exists(unreadOldA))
        assertFalse(exists(readOldB)) // 읽음이어도 age 기준이라 삭제는 됨
        assertTrue(exists(unreadRecentA))
        // 배지 동기화 대상은 안읽음이 지워진 userA 뿐 — 읽음만 지워진 userB 는 배지 불변이라 제외.
        assertEquals(setOf(userA), result.affectedUnreadByUser.keys)
        assertEquals(1L, result.affectedUnreadByUser.getValue(userA)) // 삭제 후 남은 안읽음(최근 1건)
    }

    @Test
    fun `cutoff 이전 알림은 남긴다 (경계 - created_at 이 cutoff 미만일 때만 삭제)`() {
        val userId = UUID.randomUUID()
        val recent = seed(userId)

        // created_at ≈ now 는 cutoff(now-1일) 미만이 아니므로 삭제되지 않는다(< 경계).
        val cutoff = LocalDateTime.now().minusDays(1)
        val result = notificationRetentionService.purgeOlderThan(cutoff)

        assertEquals(0, result.deletedCount) // 갓 만든 컨테이너라 1일 넘은 행이 없다
        assertTrue(exists(recent))
    }

    @Test
    fun `created_at 이 정확히 cutoff 인 알림은 삭제되지 않는다 (엄격한 미만 경계)`() {
        val userId = UUID.randomUUID()
        val boundary = seed(userId)
        // created_at 을 cutoff 과 정확히 같은 시각으로 고정 — 쿼리가 < 가 아니라 <= 로 바뀌면 이 행이 지워져 실패한다.
        val cutoff = LocalDateTime.of(2020, 1, 1, 0, 0, 0)
        setCreatedAt(boundary, cutoff)

        val result = notificationRetentionService.purgeOlderThan(cutoff)

        assertEquals(0, result.deletedCount) // created_at == cutoff 는 미만이 아니라 삭제 대상이 아니다
        assertTrue(exists(boundary))
    }
}
