package com.depromeet.piki.item.domain

import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode

enum class ItemErrorCode(
    override val code: String,
    override val category: ErrorCategory,
    override val message: String,
) : ErrorCode {
    NAME_REQUIRED_FOR_READY("ITEM-003", ErrorCategory.INVALID_INPUT, "상품 이름을 입력해 주세요."),
    PRICE_REQUIRED_FOR_READY("ITEM-004", ErrorCategory.INVALID_INPUT, "상품 가격을 입력해 주세요."),
    IMAGE_REQUIRED_FOR_READY("ITEM-005", ErrorCategory.INVALID_INPUT, "상품 이미지를 등록해 주세요."),
    QUOTA_EXCEEDED("ITEM-006", ErrorCategory.TOO_MANY_REQUESTS, "지금은 추가할 수 없어요. 잠시 후 다시 시도해 주세요."),
    NOT_REFRESHABLE("ITEM-007", ErrorCategory.INVALID_INPUT, "링크로 등록한 상품만 새로고침할 수 있어요."),
    PARSING_IN_PROGRESS("ITEM-008", ErrorCategory.CONFLICT, "아직 정보를 가져오는 중인 상품이에요. 끝난 뒤 다시 입력해 주세요."),
}
