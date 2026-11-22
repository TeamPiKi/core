package com.depromeet.piki.userreport.service.dto

import com.depromeet.piki.userreport.domain.ReportReason
import java.time.LocalDateTime
import java.util.UUID

// 운영 알림에 실리는 값(#1291).
//
// **닉네임·이메일·소셜 식별자를 담지 않는다.** 채널에 있는 사람 모두가 보는 메시지라, 사용자를 특정할 수 있는
// 값은 id 까지만 둔다. 더 봐야 하면 백오피스에서 본다.
//
// 싣는 것은 누가·누구를·왜·언제와 누적 수까지다. 가입일·회원 여부·폴더 수·위시 수 같은 신고된 사람의 프로필
// 요약은 알림에서 뺐다 — 판단할 때 백오피스에서 보면 되고, 알림마다 조회하면 질의만 늘어난다.
data class ReportNotification(
    val reporterId: UUID,
    val reportedId: UUID,
    val reason: ReportReason,
    // 그 사람이 받은 신고 수. "몇 번째 신고" 가 운영 판단의 1차 신호다
    val reportCountOfReported: Long,
    // 기타 사유의 상세. 사유가 OTHER 면 이 값이 사실상 사유라서 함께 싣는다
    val detail: String?,
    // 저장된 신고 행의 created_at. 이 앱의 JVM·DB 는 UTC 라 이 값도 UTC 다 — 표시할 때 KST 로 바꾼다
    val reportedAt: LocalDateTime,
)
