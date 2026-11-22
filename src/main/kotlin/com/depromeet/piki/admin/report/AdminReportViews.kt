package com.depromeet.piki.admin.report

import com.depromeet.piki.userreport.domain.ReportReason
import java.time.LocalDateTime
import java.util.UUID

// 백오피스 신고 화면의 모델(#1291). 템플릿이 읽는 값만 담는다.

// 목록 한 행. 신고 하나다
data class AdminReportRow(
    val reportedId: UUID,
    val reportedName: String,
    val reporterName: String,
    val reason: ReportReason,
    val reasonLabel: String,
    val detail: String?,
    // 신고 시각(KST). 저장값은 UTC 라 조회에서 바꿔 담는다
    val reportedAt: LocalDateTime,
    // 그 대상이 받은 누적 신고 수. 여러 번 신고된 사람을 한눈에 가른다
    val reportCountOfReported: Long,
    val reportedWithdrawn: Boolean,
)

// 상세 상단의 대상 요약. 제재 판단에 필요한 최소값이다
data class AdminReportedUser(
    val userId: UUID,
    val name: String,
    val joinedAt: LocalDateTime,
    val member: Boolean,
    val withdrawn: Boolean,
    val folderCount: Int,
    val wishCount: Long,
    val reportCount: Long,
)

// 상세의 신고 한 건. 대상은 상단 요약이 이미 말하므로 행에 다시 담지 않는다
data class AdminReportEntry(
    val reporterName: String,
    val reasonLabel: String,
    val detail: String?,
    val reportedAt: LocalDateTime,
)

data class AdminReportDetail(
    val reported: AdminReportedUser,
    val entries: List<AdminReportEntry>,
)
