package com.depromeet.piki.userreport.domain

import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode

// UserReportException 의 code 배정표(#1291). 번호는 append-only — 재배치·결번 침범 금지.
enum class UserReportErrorCode(
    override val code: String,
    override val category: ErrorCategory,
    override val message: String,
) : ErrorCode {
    GUEST_CANNOT_USE(
        "REPORT-001",
        ErrorCategory.FORBIDDEN,
        "신고는 회원만 할 수 있어요.",
    ),
    SELF_NOT_ALLOWED(
        "REPORT-002",
        ErrorCategory.INVALID_INPUT,
        "자기 자신은 신고할 수 없어요.",
    ),

    // 기타가 아닌 사유에 기타 내용을 함께 보낸 경우. 화면이 사유를 바꿀 때 입력을 비우므로 정상 흐름에선 안 온다
    DETAIL_NOT_ALLOWED(
        "REPORT-003",
        ErrorCategory.INVALID_INPUT,
        "그 사유에는 상세 내용을 보낼 수 없어요.",
    ),
}
