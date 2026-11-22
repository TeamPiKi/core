package com.depromeet.piki.userreport.repository

import com.depromeet.piki.userreport.domain.UserReport
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserReportJpaRepository : JpaRepository<UserReport, Long> {
    // 그 사람이 받은 신고 수. 알림이 "몇 번째 신고인지" 를 싣는다
    fun countByReportedId(reportedId: UUID): Long
}
