package com.depromeet.piki.userreport.repository

import com.depromeet.piki.profile.repository.UserCountView
import com.depromeet.piki.userreport.domain.UserReport
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface UserReportJpaRepository : JpaRepository<UserReport, Long> {
    // 그 사람이 받은 신고 수. 알림이 "몇 번째 신고인지" 를 싣는다
    fun countByReportedId(reportedId: UUID): Long

    // 백오피스 목록 — 최근 신고순. 화면이 상한을 들고 오므로 Pageable 로 받는다
    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): List<UserReport>

    // 백오피스 상세 — 한 사람이 받은 신고 전체. 운영이 패턴을 보는 자리라 끊지 않는다
    fun findAllByReportedIdOrderByCreatedAtDesc(reportedId: UUID): List<UserReport>

    // 목록 행마다 "누적 몇 건" 을 붙인다. 행마다 세면 N+1 이라 한 질의로 묶는다.
    // 결과에 없는 사용자는 0 이다(GROUP BY 는 행이 없는 사용자를 아예 돌려주지 않는다)
    @Query(
        "SELECT r.reportedId AS userId, COUNT(r) AS count FROM UserReport r " +
            "WHERE r.reportedId IN :reportedIds GROUP BY r.reportedId",
    )
    fun countByReportedIds(
        @Param("reportedIds") reportedIds: Collection<UUID>,
    ): List<UserCountView>
}
