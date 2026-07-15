package com.depromeet.piki.notification.service

import com.depromeet.piki.notification.repository.NotificationRepository
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

    @Transactional
    fun purgeOlderThan(cutoff: LocalDateTime): Int {
        val deleted = notificationRepository.deleteByCreatedAtBefore(cutoff)
        log.info("알림 보존기간 정리 — cutoff={} 삭제건수={}", cutoff, deleted)
        return deleted
    }
}
