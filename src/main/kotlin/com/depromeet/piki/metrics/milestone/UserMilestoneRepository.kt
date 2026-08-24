package com.depromeet.piki.metrics.milestone

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

// 사용자 수 마일스톤 발송 기록. 발송을 "claim" 하는 원자적 연산 하나만 노출한다.
@Repository
class UserMilestoneRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    // 임계값을 처음 claim 하면 true, 이미 있으면 false. threshold 가 PK 라 INSERT IGNORE 가 삽입 성공(affected=1)
    // 여부로 "이 호출이 최초인가" 를 원자적으로 판정한다 — 동시 가입으로 리스너가 여러 번 돌아도 딱 한 번만 true 다.
    fun tryClaim(threshold: Long): Boolean =
        jdbcTemplate.update("INSERT IGNORE INTO user_milestone_announcements (threshold) VALUES (?)", threshold) == 1

    // 주어진 임계값들 중 이미 발송(claim)된 것. 모든 임계값이 발송됐으면 announce 가 COUNT(*)를 아예 건너뛰게 하는
    // 싼 단락용(작은 테이블 PK IN 조회). thresholds 가 비면 조회하지 않는다.
    fun claimedAmong(thresholds: Collection<Long>): Set<Long> {
        if (thresholds.isEmpty()) return emptySet()
        val placeholders = thresholds.joinToString(",") { "?" }
        return jdbcTemplate
            .query(
                "SELECT threshold FROM user_milestone_announcements WHERE threshold IN ($placeholders)",
                { rs, _ -> rs.getLong("threshold") },
                *thresholds.toTypedArray(),
            ).toSet()
    }

    // claim 을 해제한다. Discord 발송이 실패한 임계값을 되돌려, 다음 가입 이벤트에서 재시도되게 한다(영구 유실 방지).
    fun release(threshold: Long) {
        jdbcTemplate.update("DELETE FROM user_milestone_announcements WHERE threshold = ?", threshold)
    }
}
