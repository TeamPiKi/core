package com.depromeet.piki.notification.service

import com.depromeet.piki.notification.repository.NotificationRepository
import com.depromeet.piki.notification.service.dto.NotificationPurgeResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

// N일 자동삭제의 순수 로직 — cutoff 이전(created_at < cutoff) 알림을 유저 무관 전부 하드삭제한다.
// 스케줄러(NotificationCleanupScheduler)가 cutoff 을 계산해 이 메서드를 호출하고, 테스트는 cutoff 을 직접 넘겨 결정적으로 검증한다.
// age 기준이라 멱등이고 commutative — blue-green 중복 실행에도 결과가 같다.
@Service
class NotificationRetentionService(
    private val notificationRepository: NotificationRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 삭제로 안읽음이 줄어든 유저를 삭제 전에 모아 두고(삭제 후엔 사라진다), 삭제 뒤 그 유저들의 안읽음을 재집계해 반환한다.
    // 배지 동기화(SSE·FCM)는 외부 호출이라 이 트랜잭션 안에서 하지 않는다 — 스케줄러가 커밋 후 이 맵을 받아 유저별로 쏜다
    // (단건·모두 삭제의 NotificationDeleteOrchestrator 와 동일한 tx 밖 배지 동기화 결).
    @Transactional
    fun purgeOlderThan(cutoff: LocalDateTime): NotificationPurgeResult {
        val affectedUsers = notificationRepository.findUserIdsWithUnreadCreatedBefore(cutoff)
        val deleted = notificationRepository.deleteByCreatedAtBefore(cutoff)
        // 유저마다 countUnread 를 돌면 N+1 이라, IN + GROUP BY 한 쿼리로 대상 유저 전원의 안읽음을 한 번에 재집계한다.
        val affectedUnreadByUser = notificationRepository.countUnreadForUsers(affectedUsers)
        log.info("알림 보존기간 정리 — cutoff={} 삭제건수={} 배지영향유저={}", cutoff, deleted, affectedUsers.size)
        return NotificationPurgeResult(deletedCount = deleted, affectedUnreadByUser = affectedUnreadByUser)
    }
}
