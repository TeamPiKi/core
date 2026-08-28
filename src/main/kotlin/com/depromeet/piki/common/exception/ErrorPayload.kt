package com.depromeet.piki.common.exception

// "무엇과 충돌했는지" 가 응답 계약의 일부인 예외가 구현한다(#973 중복 등록).
// GlobalExceptionHandler 가 이 값을 에러 응답의 data 로 싣는다 — 에러 응답이 code·detail 만으로
// 못 주는 맥락(예: 이미 담긴 위시의 id)을 클라가 한 번의 왕복으로 받게 한다.
//
// RetryAfter 와 같은 결로 HttpMappable(status·category)과 분리한다: 대다수 사유는 실어 보낼 맥락이 없다.
// 다만 이 프로젝트의 도메인 예외는 사유가 클래스가 아니라 **팩토리 단위로 갈리므로**(WishException 하나가
// 모든 위시 사유를 다룬다), RetryAfter 처럼 "구현 여부"로는 가릴 수 없어 값을 nullable 로 둔다.
// 핸들러는 null 이면 data 를 싣지 않는다 — 맥락 없는 사유에 빈 객체가 실리지 않는다.
interface ErrorPayload {
    val payload: Map<String, Any>?
}
