package com.depromeet.piki.notification.domain

import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode

// NotificationException 의 code 배정표(에픽 #728). 번호는 append-only — 재배치·결번 침범 금지.
// code·category·message 를 한 엔트리에 모아 single source 로 둔다: status 는 category.httpStatus 로,
// 응답 detail·로그·OpenAPI 문서·코드 카탈로그는 message 로 파생된다.
//
// INVALID_CURSOR message 는 구 "유효하지 않은 cursor 입니다." 에서 wish·announcement 와 동일한
// 사용자 친화 문구로 통일한다(에픽 #763). 커서는 내부 페이지네이션 구현이라 "cursor" 라는 기술용어를
// 사용자 응답 detail 로 노출하지 않는다(CLAUDE.md 메시지 톤).
enum class NotificationErrorCode(
    override val code: String,
    override val category: ErrorCategory,
    override val message: String,
) : ErrorCode {
    INVALID_CURSOR("NOTIFICATION-001", ErrorCategory.INVALID_INPUT, "페이지를 불러오지 못했어요. 새로고침 해주세요."),
}
