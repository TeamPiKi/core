package com.depromeet.piki.common.exception

// "무엇과 충돌했는지" 가 응답 계약의 일부인 예외가 구현한다(#973 중복 등록).
// GlobalExceptionHandler 가 이 값을 에러 응답의 data 로 싣는다 — 사유(code)만으로는 클라가 다음 행동을
// 정할 수 없는 경우에, 그 맥락을 한 번의 왕복으로 준다.
//
// RetryAfter 와 같은 결이다: 값이 non-null 이라 **구현 여부가 곧 data 유무**이고, 핸들러는 as? 로 가리기만 한다.
// 그래서 이 인터페이스를 구현하는 예외는 사유 하나만 담는 전용 클래스여야 한다(AlreadyRegisteredException 참고) —
// 여러 사유를 한 클래스로 다루는 도메인 예외에 nullable 필드로 얹으면 그 구분이 사라진다.
interface ErrorPayload {
    // 응답 data 로 그대로 직렬화된다. 필드명이 클라 계약이므로 맵이 아니라 프로퍼티를 가진 타입을 싣는다.
    val payload: Any
}
