package com.depromeet.piki.userreport.repository

import com.depromeet.piki.userreport.domain.UserReport
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class UserReportRepositoryImpl(
    private val userReportJpaRepository: UserReportJpaRepository,
) : UserReportRepository {
    override fun save(report: UserReport): UserReport = userReportJpaRepository.save(report)

    override fun countByReportedId(reportedId: UUID): Long = userReportJpaRepository.countByReportedId(reportedId)
}
