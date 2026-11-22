package com.depromeet.piki.userreport.repository

import com.depromeet.piki.userreport.domain.UserReport
import java.util.UUID

interface UserReportRepository {
    fun save(report: UserReport): UserReport

    fun countByReportedId(reportedId: UUID): Long
}
