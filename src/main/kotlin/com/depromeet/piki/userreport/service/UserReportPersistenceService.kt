package com.depromeet.piki.userreport.service

import com.depromeet.piki.block.service.UserBlockService
import com.depromeet.piki.folder.repository.FolderRepository
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.User
import com.depromeet.piki.user.domain.UserException
import com.depromeet.piki.user.service.UserService
import com.depromeet.piki.userreport.domain.ReportReason
import com.depromeet.piki.userreport.domain.UserReport
import com.depromeet.piki.userreport.domain.UserReportException
import com.depromeet.piki.userreport.repository.UserReportRepository
import com.depromeet.piki.userreport.service.dto.ReportNotification
import com.depromeet.piki.wishlist.repository.WishRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 신고 저장과 차단 적용을 한 트랜잭션에 묶는다(#1291).
 *
 * [UserReportService] 와 분리한 이유: 외부 호출(디스코드 알림)을 트랜잭션 밖으로 빼려면 영속화가 별도 빈이어야
 * 한다. 같은 빈 안에서 부르면 proxy 를 거치지 않아 `@Transactional` 이 걸리지 않는다(CLAUDE.md 트랜잭션 경계).
 *
 * 차단을 같은 트랜잭션에 두는 이유: 신고는 됐는데 차단이 빠진 상태가 남으면, 사용자는 신고한 상대를 계속 본다.
 * 둘 중 하나만 성공하는 경우를 만들지 않는다.
 */
@Service
class UserReportPersistenceService(
    private val userReportRepository: UserReportRepository,
    private val userBlockService: UserBlockService,
    private val userService: UserService,
    private val folderRepository: FolderRepository,
    private val wishRepository: WishRepository,
) {
    @Transactional
    fun saveAndBlock(
        reporterId: UUID,
        reportedId: UUID,
        reason: ReportReason,
        detail: String?,
    ): ReportNotification {
        if (reporterId == reportedId) throw UserReportException.selfNotAllowed()
        // 기타가 아닌 사유에 상세가 함께 오면 거른다. 화면이 사유를 바꿀 때 입력을 비우므로 정상 흐름에선 안 온다
        detail?.takeIf { it.isNotBlank() }?.let {
            if (!reason.allowsDetail()) throw UserReportException.detailNotAllowed()
        }
        requireMember(userService.findActiveById(reporterId))
        val reported = activeTarget(reportedId)

        userReportRepository.save(
            UserReport(
                reporterId = reporterId,
                reportedId = reportedId,
                reason = reason,
                detail = detail?.takeIf { it.isNotBlank() },
            ),
        )
        // 신고는 차단을 동반한다(기획 확정). 이미 차단했으면 그 서비스가 멱등으로 통과시킨다
        userBlockService.block(reporterId, reportedId)

        return notificationOf(reporterId, reported, reason, detail)
    }

    // 알림에 실을 값을 트랜잭션 안에서 모은다 — 밖에서 모으면 그 사이 탈퇴로 조회가 404 가 될 수 있다.
    // 폴더·위시 수는 운영이 보는 지표라 공개 여부를 가리지 않고 전체를 센다(프로필 화면의 공개 집계와 다르다).
    private fun notificationOf(
        reporterId: UUID,
        reported: User,
        reason: ReportReason,
        detail: String?,
    ): ReportNotification {
        val reportedId = reported.id
        return ReportNotification(
            reportedId = reportedId,
            reportCountOfReported = userReportRepository.countByReportedId(reportedId),
            reportedJoinedAt = reported.createdAt,
            reportedIsMember = reported.identityType == IdentityType.MEMBER,
            reportedFolderCount = folderRepository.findAllByUserId(reportedId).size,
            reportedWishCount = wishRepository.countByUserId(reportedId),
            reporterId = reporterId,
            reason = reason,
            detail = detail?.takeIf { it.isNotBlank() },
        )
    }

    // 게스트는 신고할 수 없다. 친구·차단과 같은 결이다
    private fun requireMember(user: User) {
        user.deletedAt?.let { throw UserException.deletedUser() }
        if (user.identityType != IdentityType.MEMBER) throw UserReportException.guestCannotUse()
    }

    // 상대의 부재와 탈퇴를 같은 404 로 묶는다 — 가르면 "그 계정이 있었다" 가 드러난다
    private fun activeTarget(reportedId: UUID): User {
        val user = userService.findActiveByIds(listOf(reportedId)).firstOrNull() ?: throw UserException.notFound()
        return user
    }
}
