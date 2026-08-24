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
}
