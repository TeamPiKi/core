package com.depromeet.piki.item.domain

import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode

// 번호는 append-only. 결번 ITEM-001·ITEM-002 는 수기 수정 상시 허용(#825)으로 폐기했고 재사용하지 않는다
enum class ItemErrorCode(
    override val code: String,
    override val category: ErrorCategory,
    override val message: String,
) : ErrorCode {
    // 필수값 누락 셋은 합치지 않는다 — manual 의 순차 guard 가 각각 다른 필드를 가리키므로,
    // code 를 나눠 둬야 클라가 "어느 필드가 비었는지" 를 안내할 수 있다.
    NAME_REQUIRED_FOR_READY("ITEM-003", ErrorCategory.INVALID_INPUT, "상품 이름을 입력해 주세요."),
    PRICE_REQUIRED_FOR_READY("ITEM-004", ErrorCategory.INVALID_INPUT, "상품 가격을 입력해 주세요."),
    IMAGE_REQUIRED_FOR_READY("ITEM-005", ErrorCategory.INVALID_INPUT, "상품 이미지를 등록해 주세요."),

    // 006 은 한도 code 통합(WISH-010·TOURNAMENT-037 대체)에서 추가됐다. 한도는 아이템 등록의 사실이라
    // 담는 자리(위시·토너먼트)마다 code 를 나눌 이유가 없다 — 카운터도 하나다.
    // 문구는 몫의 주인을 드러내지 않는 쪽으로 고정한다: 토너먼트는 오너 몫에서 깎지만 이 응답은 참여 게스트도
    // 받으므로, 남의 사용량이 문구로 새면 안 된다. 남은 시간은 문구가 아니라 Retry-After 헤더가 전한다.
    QUOTA_EXCEEDED("ITEM-006", ErrorCategory.TOO_MANY_REQUESTS, "지금은 추가할 수 없어요. 잠시 후 다시 시도해 주세요."),
    NOT_REFRESHABLE("ITEM-007", ErrorCategory.INVALID_INPUT, "링크로 등록한 상품만 새로고침할 수 있어요."),
}
