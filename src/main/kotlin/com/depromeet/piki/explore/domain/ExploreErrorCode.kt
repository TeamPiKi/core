package com.depromeet.piki.explore.domain

import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode

// ExploreException 의 code 배정표. 번호는 append-only
enum class ExploreErrorCode(
    override val code: String,
    override val category: ErrorCategory,
    override val message: String,
) : ErrorCode {
    // 지운 기록과 가려진 사람의 기록을 같은 응답으로 숨긴다. 가르면 차단·비공개 사실이 드러난다
    PICK_NOT_FOUND(
        "EXPLORE-001",
        ErrorCategory.NOT_FOUND,
        "토너먼트 결과를 찾을 수 없어요.",
    ),
}
