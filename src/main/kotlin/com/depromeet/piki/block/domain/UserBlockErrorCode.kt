package com.depromeet.piki.block.domain

import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode

// UserBlockException 의 code 배정표(#1289). 번호는 append-only — 재배치·결번 침범 금지.
enum class UserBlockErrorCode(
    override val code: String,
    override val category: ErrorCategory,
    override val message: String,
) : ErrorCode {
    // 게스트는 차단을 쓸 수 없다. 친구 기능(FRIENDSHIP-001)과 같은 결
    GUEST_CANNOT_USE(
        "BLOCK-001",
        ErrorCategory.FORBIDDEN,
        "차단 기능은 회원만 쓸 수 있어요.",
    ),
    SELF_NOT_ALLOWED(
        "BLOCK-002",
        ErrorCategory.INVALID_INPUT,
        "자기 자신은 차단할 수 없어요.",
    ),
}
