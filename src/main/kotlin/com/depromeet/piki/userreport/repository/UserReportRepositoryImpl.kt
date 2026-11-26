package com.depromeet.piki.userreport.repository

import com.depromeet.piki.userreport.domain.UserReport
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class UserReportRepositoryImpl(
    private val userReportJpaRepository: UserReportJpaRepository,
) : UserReportRepository {
    override fun save(report: UserReport): UserReport = userReportJpaRepository.save(report)

    override fun countByReportedId(reportedId: UUID): Long = userReportJpaRepository.countByReportedId(reportedId)

    override fun findRecent(limit: Int): List<UserReport> =
        userReportJpaRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit))

    override fun findAllByReportedId(reportedId: UUID): List<UserReport> =
        userReportJpaRepository.findAllByReportedIdOrderByCreatedAtDesc(reportedId)

    override fun countsByReportedIds(reportedIds: Collection<UUID>): Map<UUID, Long> {
        // IN () 는 문법 오류다. 빈 입력은 질의 없이 끝낸다
        if (reportedIds.isEmpty()) return emptyMap()
        return userReportJpaRepository.countByReportedIds(reportedIds).associate { it.userId to it.count }
    }
}
