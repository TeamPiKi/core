package com.depromeet.piki.image.domain

import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode

// ProductImageException 의 code 배정표(에픽 #728). 번호는 append-only — 재배치·결번 침범 금지.
// code·category·message 를 한 엔트리에 모아 single source 로 둔다: status 는 category.httpStatus 로,
// 응답 detail·로그·OpenAPI 카탈로그는 message 로 파생된다.
//
// prefix 가 PRODUCT-IMAGE 가 아니라 붙여쓴 PRODUCTIMAGE 인 이유는 ImageProxyErrorCode 주석과 같다
// (공개 code 형식 가드가 글자와 숫자를 섞은 3세그먼트를 허용하지 않는다).
enum class ProductImageErrorCode(
    override val code: String,
    override val category: ErrorCategory,
    override val message: String,
) : ErrorCode {
    EMPTY_IMAGE("PRODUCTIMAGE-001", ErrorCategory.INVALID_INPUT, "빈 이미지 파일은 올릴 수 없어요."),

    // 매직바이트로도 형식을 판별하지 못함(깨진 파일 등).
    UNKNOWN_TYPE("PRODUCTIMAGE-002", ErrorCategory.INVALID_INPUT, "이미지 형식을 확인할 수 없어요."),

    // 형식은 판별했으나 지원 목록 밖.
    UNSUPPORTED_TYPE("PRODUCTIMAGE-003", ErrorCategory.INVALID_INPUT, "지원하지 않는 이미지 형식이에요."),
}
