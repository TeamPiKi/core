package com.depromeet.piki.product.domain

import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode

// ProductLinkException 의 code 배정표(에픽 #728). 번호는 append-only — 재배치·결번 침범 금지.
// code·category·message 를 한 엔트리에 모아 single source 로 둔다: status 는 category.httpStatus 로,
// 응답 detail·로그·OpenAPI 카탈로그는 message 로 파생된다.
//
// 3개 전부 공개 JSON API 도달이라 ErrorCodeRegistry 에 등록한다. 링크 등록 경계(ProductLink.of ·
// ExtractionRoutingPolicy)가 위시 등록(POST /wishlists)·토너먼트 아이템 등록(POST /tournaments/{id}/items)
// 양쪽에서 GlobalExceptionHandler 를 거쳐 wire code 로 나간다(WishlistApiExamples·TournamentItemApiExamples 문서화).
//
// 빈 링크에는 code 를 배정하지 않는다 — 두 등록 DTO 가 @field:NotBlank 로 막아 컨트롤러 진입 전에
// MethodArgumentNotValidException(COMMON-INVALID-INPUT)으로 끊기므로, 멀쩡한 클라이언트가 정상 요청으로
// ProductLink.parse 의 blank 분기에 닿을 수 없다. 도달 불가 code 를 카탈로그에 두면 클라의 code→문구
// 매핑에 노이즈만 된다(SNAPSHOT·EXTRACTOR 미등록과 같은 기준). 도메인 쪽 빈 값 방어는 계약이 아닌
// 불변식이라 parse 안의 require 가 맡는다.
//
// 같은 400·INVALID_INPUT 이지만 code 를 3개로 나눈다 — 사용자가 취할 행동이 갈리기 때문이다:
// 형식 오류는 정정, https 아님은 주소 교체, 미지원 몰은 이미지 직접 등록으로 우회.
enum class ProductLinkErrorCode(
    override val code: String,
    override val category: ErrorCategory,
    override val message: String,
) : ErrorCode {
    INVALID_FORMAT("LINK-001", ErrorCategory.INVALID_INPUT, "올바른 링크 형식이 아니에요. 다시 확인해 주세요."),
    UNSUPPORTED_SCHEME("LINK-002", ErrorCategory.INVALID_INPUT, "https 링크만 등록할 수 있어요."),
    UNSUPPORTED_PLATFORM("LINK-003", ErrorCategory.INVALID_INPUT, "아직 지원하지 않는 쇼핑몰이에요. 상품 이미지를 직접 등록해 주세요."),
}
