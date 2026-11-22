package com.depromeet.piki.friendship.domain

import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode

// FriendshipException 의 code 배정표(#1288). 번호는 append-only — 재배치·결번 침범 금지.
enum class FriendshipErrorCode(
    override val code: String,
    override val category: ErrorCategory,
    override val message: String,
) : ErrorCode {
    // 게스트는 친구를 만들 수 없다. 앱은 이 응답을 받아 로그인 안내 모달을 띄운다
    GUEST_CANNOT_USE(
        "FRIENDSHIP-001",
        ErrorCategory.FORBIDDEN,
        "친구 기능은 회원만 쓸 수 있어요.",
    ),
    SELF_NOT_ALLOWED(
        "FRIENDSHIP-002",
        ErrorCategory.INVALID_INPUT,
        "자기 자신은 친구로 추가할 수 없어요.",
    ),
}
