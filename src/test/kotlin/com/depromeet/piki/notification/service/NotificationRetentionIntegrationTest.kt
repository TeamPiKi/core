package com.depromeet.piki.notification.service

import com.depromeet.piki.notification.domain.Notification
import com.depromeet.piki.notification.domain.NotificationType
import com.depromeet.piki.notification.repository.NotificationJpaRepository
import com.depromeet.piki.notification.repository.NotificationRepository
import com.depromeet.piki.support.IntegrationTestSupport
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

    private fun seed(userId: UUID): Long =
        notificationRepository
            .save(Notification(userId, NotificationType.ITEM_PARSING_COMPLETED, "제목", "본문", 11L))
            .getId()

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
    fun `cutoff 이전 알림은 남긴다 (경계 - created_at 이 cutoff 미만일 때만 삭제)`() {
        val userId = UUID.randomUUID()
        val recent = seed(userId)

        // created_at ≈ now 는 cutoff(now-1일) 미만이 아니므로 삭제되지 않는다(< 경계).
        val cutoff = LocalDateTime.now().minusDays(1)
        val deleted = notificationRetentionService.purgeOlderThan(cutoff)

        assertEquals(0, deleted) // 갓 만든 컨테이너라 1일 넘은 행이 없다
        assertTrue(exists(recent))
    }
}
