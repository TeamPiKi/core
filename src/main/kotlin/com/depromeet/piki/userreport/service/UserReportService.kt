package com.depromeet.piki.userreport.service

import com.depromeet.piki.userreport.domain.ReportReason
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * 사용자 신고(#1291).
 *
 * **신고는 차단을 동반한다**(기획 확정). 신고한 사람은 그 상대를 더 보고 싶지 않다는 뜻이므로,
 * 신고만 받고 계속 노출하면 사용자가 한 번 더 차단을 눌러야 한다.
 *
 * 이 빈은 트랜잭션을 열지 않는다 — 저장은 [UserReportPersistenceService] 의 짧은 트랜잭션이 맡고,
 * 디스코드 알림(외부 호출)은 그 트랜잭션이 **끝난 뒤** 밖에서 보낸다.
 * 외부 호출을 트랜잭션에 넣으면 read-timeout 동안 DB 커넥션을 쥐어 다른 API 까지 느려진다(CLAUDE.md 트랜잭션 경계).
 */
@Service
class UserReportService(
    private val persistence: UserReportPersistenceService,
    private val reportNotifier: ReportNotifier,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 신고 접수. 같은 처리 안에서 차단까지 적용한다.
     *
     * 같은 상대를 여러 번 신고할 수 있다 — 막지 않는다. 신고는 운영 판단의 입력이고,
     * 반복 신고 자체가 신호다. 중복을 막으면 "이미 신고했어요" 를 사용자에게 설명해야 한다.
     *
     * 알림 실패는 삼킨다. 신고는 이미 저장됐고, 운영이 알림을 못 받는 것과 사용자의 신고가 실패하는 것은
     * 무게가 다르다.
     */
    fun report(
        reporterId: UUID,
        reportedId: UUID,
        reason: ReportReason,
        detail: String?,
    ) {
        val notification = persistence.saveAndBlock(reporterId, reportedId, reason, detail)
        runCatching { reportNotifier.notify(notification) }
            .onFailure { log.warn("신고 알림 전달 실패 reportedId={}", reportedId, it) }
    }
}
