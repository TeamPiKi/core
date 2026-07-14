package com.depromeet.piki.common.exception

// 클라이언트 대면 문구를 서버가 소유하던 detail 을 code 로 이관하기 위한 계약 키(에픽 #728).
// wire 로 나가는 값은 code(예: "USER-001") 문자열이고, status 는 category 가 소유한다(category.httpStatus).
// 예외 클래스별로 이 인터페이스를 구현한 enum(UserErrorCode 등)을 두어 도메인 경계·번호 안정성을 유지한다.
interface ErrorCode {
    val code: String
    val category: ErrorCategory

    // 이 message 는 사용자에게 보일 '최종 문구'가 아니다 — 최종 문구의 소유권은 클라이언트에 있고, 클라가 code 로 매핑한다.
    // 서버의 이 message 는 그 code 가 무엇을 뜻하는지 설명하는 개발자·문서용 정본이다: OpenAPI 카탈로그 '의미' 열과
    // 서버 로그가 이걸 참조한다. 다만 전환기(detail 제거 전) 동안만 응답 detail 로도 함께 나가므로, 노출을 전제로
    // 사용자 친화 톤을 유지하고 내부 식별자·기술용어는 넣지 않는다(구체 디버깅 정보는 로그·cause 로).
    // 문구를 이 한 자리에만 두어 문서·로그·detail 사이 drift 를 차단한다. status 는 category.httpStatus 로 파생된다.
    val message: String
}
