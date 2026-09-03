package com.depromeet.piki.item.domain

import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode

// ItemException 의 code 배정표(에픽 #728). 번호는 append-only — 재배치·결번 침범 금지.
// code·category·message 를 한 엔트리에 모아 single source 로 둔다: status 는 category.httpStatus 로,
// 응답 detail·로그·OpenAPI 카탈로그는 message 로 파생된다.
//
// 공개 JSON API 도달이라 ErrorCodeRegistry 에 등록한다(어드민 SSR 전용이라 미등록인
// AnnouncementImageErrorCode 와 갈리는 지점). 던지는 곳은 ItemSnapshot.manual(수기 수정 병합 검증)이며,
// 위시 보정(PATCH /wishlists/{id})·토너먼트 아이템 보정(PATCH /tournaments/{id}/items/{itemId}) 두
// 엔드포인트에서 GlobalExceptionHandler 를 거쳐 wire code 로 나간다.
//
// **결번**: ITEM-001(ALREADY_READY)·ITEM-002(STILL_PROCESSING)는 수기 수정 상시 허용(#825 결정 4,
// 2026-07-31)으로 폐기됐다. 번호는 재사용하지 않는다.
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
}
