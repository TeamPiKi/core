package com.depromeet.piki.wishlist.domain

import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode

// WishException 의 code 배정표(에픽 #728). 번호는 append-only — 재배치·결번 침범 금지.
// code·category·message 를 한 엔트리에 모아 single source 로 둔다: status 는 category.httpStatus 로,
// 응답 detail·로그·OpenAPI 카탈로그는 message 로 파생된다.
// INVALID_CURSOR 문구는 notification·announcement 이관과 통일된 사용자 친화 문구를 유지한다.
enum class WishErrorCode(
    override val code: String,
    override val category: ErrorCategory,
    override val message: String,
) : ErrorCode {
    GUEST_CANNOT_USE_WISHLIST("WISH-001", ErrorCategory.FORBIDDEN, "위시리스트는 회원만 이용할 수 있어요."),
    FORBIDDEN_WISH_ITEMS("WISH-002", ErrorCategory.FORBIDDEN, "내 위시 아이템만 볼 수 있어요."),
    INVALID_CURSOR("WISH-003", ErrorCategory.INVALID_INPUT, "페이지를 불러오지 못했어요. 새로고침 해주세요."),
    NOT_FOUND("WISH-004", ErrorCategory.NOT_FOUND, "이미 삭제된 아이템이에요."),
    INVALID_IMAGE_COUNT("WISH-005", ErrorCategory.INVALID_INPUT, "이미지는 1~5장만 올릴 수 있어요."),
    INVALID_ID_COUNT("WISH-006", ErrorCategory.INVALID_INPUT, "한 번에 최대 100개까지 삭제할 수 있어요."),
    NOT_REFRESHABLE("WISH-007", ErrorCategory.INVALID_INPUT, "링크가 없는 항목은 새로고침할 수 없습니다."),
    FAILED_NOT_REFRESHABLE(
        "WISH-008",
        ErrorCategory.CONFLICT,
        "추출에 실패한 항목은 새로고침 대신 정보를 직접 입력해 복구해 주세요.",
    ),
}
