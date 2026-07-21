package com.depromeet.piki.announcement.domain

import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode

// AnnouncementException 의 code 배정표(에픽 #728). 번호는 append-only — 재배치·결번 침범 금지.
// code·category·message 를 한 엔트리에 모아 single source 로 둔다: status 는 category.httpStatus 로,
// 응답 detail·로그·OpenAPI 문서·코드 카탈로그는 message 로 파생된다.
// message 는 사용자 대면 고정 문구 — 미발송 공지의 존재 등 내부 정보를 노출하지 않는다.
enum class AnnouncementErrorCode(
    override val code: String,
    override val category: ErrorCategory,
    override val message: String,
) : ErrorCode {
    NOT_FOUND("ANNOUNCEMENT-001", ErrorCategory.NOT_FOUND, "존재하지 않는 공지예요."),
    INVALID_CURSOR("ANNOUNCEMENT-002", ErrorCategory.INVALID_INPUT, "페이지를 불러오지 못했어요. 새로고침 해주세요."),
}
