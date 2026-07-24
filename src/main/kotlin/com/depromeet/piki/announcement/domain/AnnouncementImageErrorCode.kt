package com.depromeet.piki.announcement.domain

import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode

// AnnouncementImageException 의 code 배정표(에픽 #728). 번호는 append-only — 재배치·결번 침범 금지.
//
// ⚠️ 이 enum 은 ErrorCodeRegistry.all 에 **의도적으로 등록하지 않는다**. AnnouncementImageException 은
// 공지 본문 이미지 rehost(어드민 백오피스, Thymeleaf SSR) 전용이라 AdminAnnouncementController 가 자체 catch 해
// 리다이렉트로 처리한다 — GlobalExceptionHandler 를 거치지 않아 응답 code 로 wire 에 실리지 않는다.
// 따라서 공개 OpenAPI 카탈로그(클라 code→문구 매핑용)에 넣으면 클라가 절대 못 받는 code 로 노이즈만 된다.
// 등록하지 않으므로 ErrorCodeCatalogTest 의 공개 형식(PREFIX-NNN 단일 토큰)·유니크 검증 대상도 아니다.
// 여기서 code 를 부여하는 목적은 오직 예외 클래스 모양을 다른 도메인 예외와 통일(errorCode 참조)하는 것뿐이다.
enum class AnnouncementImageErrorCode(
    override val code: String,
    override val category: ErrorCategory,
    override val message: String,
) : ErrorCode {
    // 지원 목록(png·jpeg·gif·webp) 밖의 형식.
    UNSUPPORTED_TYPE("ANNOUNCEMENT-IMAGE-001", ErrorCategory.INVALID_INPUT, "지원하지 않는 이미지 형식입니다. (png·jpg·gif·webp 만 가능)"),

    // 선언한 형식과 실제 바이트(매직바이트)가 다르거나 깨진 파일.
    MALFORMED("ANNOUNCEMENT-IMAGE-002", ErrorCategory.INVALID_INPUT, "이미지 파일이 올바르지 않습니다."),

    // 허용 용량 초과.
    TOO_LARGE("ANNOUNCEMENT-IMAGE-003", ErrorCategory.INVALID_INPUT, "이미지 용량이 너무 큽니다."),

    // 외부 주소에서 이미지를 가져오지 못함(네트워크·404·이미지 아님 등). 구체 사유는 로그로 남긴다.
    FETCH_FAILED("ANNOUNCEMENT-IMAGE-004", ErrorCategory.INVALID_INPUT, "이미지 주소에서 이미지를 가져오지 못했습니다. 주소를 확인해주세요."),

    // SSRF 방어로 차단한 주소(https 아님 · 사설/내부 IP). 차단 사유는 노출하지 않는다(로그로만).
    BLOCKED_URL("ANNOUNCEMENT-IMAGE-005", ErrorCategory.INVALID_INPUT, "허용되지 않는 이미지 주소입니다."),
}
