package com.depromeet.piki.admin.report

import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import com.depromeet.piki.folder.repository.FolderRepository
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.User
import com.depromeet.piki.user.repository.UserRepository
import com.depromeet.piki.userreport.repository.UserReportRepository
import com.depromeet.piki.wishlist.repository.WishRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * 백오피스 신고 조회(#1291). 읽기만 한다 — 제재(정지·삭제) 조치는 이 단계의 범위가 아니다.
 *
 * 닉네임을 보여준다. 운영자만 보는 화면이라 디스코드 알림(채널 전원이 봄)과 판단 기준이 다르다 —
 * 알림은 id 까지만 싣고, 사람을 특정해 보는 일은 이 화면이 맡는다.
 *
 * 탈퇴한 사용자도 보여준다. 신고 기록은 탈퇴해도 남으므로(#1291) 그 행의 대상이 사라지면 안 된다.
 */
@Service
@ConditionalOnAdminEnabled
class AdminUserReportService(
    private val userReportRepository: UserReportRepository,
    private val userRepository: UserRepository,
    private val folderRepository: FolderRepository,
    private val wishRepository: WishRepository,
) {
    @Transactional(readOnly = true)
    fun recent(limit: Int = DEFAULT_LIMIT): List<AdminReportRow> {
        val reports = userReportRepository.findRecent(limit)
        // 두 사람 묶어 한 번에 읽는다 — 행마다 읽으면 N+1. 탈퇴자도 들어오게 삭제 가드 없는 조회를 쓴다
        val users = usersOf(reports.flatMap { listOf(it.reporterId, it.reportedId) })
        val counts = userReportRepository.countsByReportedIds(reports.map { it.reportedId }.toSet())
        return reports.map { report ->
            val reported = users[report.reportedId]
            AdminReportRow(
                reportedId = report.reportedId,
                reportedName = nameOf(reported),
                reporterName = nameOf(users[report.reporterId]),
                reason = report.reason,
                reasonLabel = report.reason.label,
                detail = report.detail,
                reportedAt = kstOf(report.createdAt),
                reportCountOfReported = counts[report.reportedId] ?: 0,
                reportedWithdrawn = reported?.isActive()?.not() ?: true,
            )
        }
    }

    /** 한 사람이 받은 신고 전체. 대상이 없으면(id 오타 등) null — 화면이 404 로 돌린다. */
    @Transactional(readOnly = true)
    fun detailOf(reportedId: UUID): AdminReportDetail? {
        val reports = userReportRepository.findAllByReportedId(reportedId)
        val reported = userRepository.findByIds(listOf(reportedId)).firstOrNull()
        // 사용자도 신고도 없으면 보여줄 것이 없다. 사용자가 없고 신고만 있는 경우는 보여준다(행 자체가 기록이다)
        if (reports.isEmpty()) reported ?: return null
        val reporters = usersOf(reports.map { it.reporterId })
        return AdminReportDetail(
            reported = summaryOf(reportedId, reported, reports.size.toLong()),
            entries =
                reports.map {
                    AdminReportEntry(
                        reporterName = nameOf(reporters[it.reporterId]),
                        reasonLabel = it.reason.label,
                        detail = it.detail,
                        reportedAt = kstOf(it.createdAt),
                    )
                },
        )
    }

    private fun summaryOf(
        reportedId: UUID,
        reported: User?,
        reportCount: Long,
    ): AdminReportedUser {
        reported ?: return AdminReportedUser(
            userId = reportedId,
            name = UNKNOWN_NAME,
            joinedAt = LocalDateTime.MIN,
            member = false,
            withdrawn = true,
            folderCount = 0,
            wishCount = 0,
            reportCount = reportCount,
        )
        // 폴더·위시 수는 공개 여부를 가리지 않고 전체를 센다 — 운영이 보는 지표라 프로필 화면의 공개 집계와 다르다
        return AdminReportedUser(
            userId = reportedId,
            name = reported.nickname,
            joinedAt = kstOf(reported.createdAt),
            member = reported.identityType == IdentityType.MEMBER,
            withdrawn = !reported.isActive(),
            folderCount = folderRepository.findAllByUserId(reportedId).size,
            wishCount = wishRepository.countByUserId(reportedId),
            reportCount = reportCount,
        )
    }

    private fun usersOf(ids: Collection<UUID>): Map<UUID, User> {
        val unique = ids.toSet()
        if (unique.isEmpty()) return emptyMap()
        return userRepository.findByIds(unique).associateBy { it.id }
    }

    // 사용자 행이 하드 삭제된 옛 데이터도 행 자체는 보여준다 — 신고 기록이 사라지면 패턴을 못 본다
    private fun nameOf(user: User?): String = user?.nickname ?: UNKNOWN_NAME

    // 저장값은 UTC 다(JVM·DB 둘 다 UTC). 운영이 읽는 화면이라 KST 로 바꿔 담는다
    private fun kstOf(utc: LocalDateTime): LocalDateTime =
        utc
            .atZone(ZoneOffset.UTC)
            .withZoneSameInstant(KST)
            .toLocalDateTime()

    companion object {
        // 운영이 훑는 자리라 최근 것만 본다. 더 거슬러 봐야 하면 대상 상세로 들어간다
        const val DEFAULT_LIMIT = 100
        private const val UNKNOWN_NAME = "(알 수 없음)"
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
