package com.depromeet.piki.userreport.service.dto

import com.depromeet.piki.userreport.domain.ReportReason
import java.time.LocalDateTime
import java.util.UUID

// 운영 알림에 실리는 값(#1291).
//
// **닉네임·이메일·소셜 식별자를 담지 않는다.** 채널에 있는 사람 모두가 보는 메시지라, 사용자를 특정할 수 있는
// 값은 id 까지만 둔다. 더 봐야 하면 백오피스에서 본다.
data class ReportNotification(
    val reportedId: UUID,
    // 그 사람이 받은 신고 수. "몇 번째 신고" 가 운영 판단의 1차 신호다
    val reportCountOfReported: Long,
    val reportedJoinedAt: LocalDateTime,
    val reportedIsMember: Boolean,
    val reportedFolderCount: Int,
    val reportedWishCount: Long,
    val reporterId: UUID,
    val reason: ReportReason,
    val detail: String?,
)
