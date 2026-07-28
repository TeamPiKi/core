package com.depromeet.piki.common.imageproxy

import com.depromeet.piki.common.exception.ErrorCategory
import com.depromeet.piki.common.exception.ErrorCode

// ImageProxyException 의 code 배정표(에픽 #728). 번호는 append-only — 재배치·결번 침범 금지.
// code·category·message 를 한 엔트리에 모아 single source 로 둔다: status 는 category.httpStatus 로,
// 응답 detail·로그·OpenAPI 카탈로그는 message 로 파생된다.
//
// prefix 가 IMAGE-PROXY 가 아니라 단일 토큰 PROXY 인 이유: 공개 카탈로그의 code 형식 가드가
// PREFIX-NNN(첫 세그먼트 뒤는 숫자 3자리 또는 전부 글자)만 허용해, 글자와 숫자를 섞은 3세그먼트는 통과하지 못한다.
// 코드베이스에 프록시 예외가 이 클래스 하나뿐이라 한 단어로도 뜻이 흐려지지 않는다(#800 에서 확정).
enum class ImageProxyErrorCode(
    override val code: String,
    override val category: ErrorCategory,
    override val message: String,
) : ErrorCode {
    // SSRF 방어로 차단한 주소(https 아님 · 호스트 없음 · 허용 목록 밖). 차단 사유는 노출하지 않는다(로그로만).
    BLOCKED_DOMAIN("PROXY-001", ErrorCategory.INVALID_INPUT, "허용되지 않은 이미지 도메인입니다."),

    // 스트리밍 중 상한을 넘어선 이미지. 끝까지 읽지 않고 중단한다.
    IMAGE_TOO_LARGE("PROXY-002", ErrorCategory.INVALID_INPUT, "이미지 크기가 너무 큽니다."),

    // 외부 이미지 서버 호출 실패(응답 오류·이미지 아님·본문 없음). 우리 밖 의존성이라 재시도로 대응 가능.
    FETCH_FAILED("PROXY-003", ErrorCategory.RETRYABLE, "이미지를 불러올 수 없습니다."),
}
