package com.depromeet.piki.userreport.repository

import com.depromeet.piki.userreport.domain.UserReport
import java.util.UUID

interface UserReportRepository {
    fun save(report: UserReport): UserReport

    fun countByReportedId(reportedId: UUID): Long

    // 백오피스 목록(최근순). limit 는 화면이 정한다
    fun findRecent(limit: Int): List<UserReport>

    // 백오피스 상세 — 한 사람이 받은 신고 전체
    fun findAllByReportedId(reportedId: UUID): List<UserReport>

    // 신고된 사람 id 별 누적 신고 수. 목록이 행마다 세지 않게 한 질의로 묶는다
    fun countsByReportedIds(reportedIds: Collection<UUID>): Map<UUID, Long>
}
