package com.depromeet.piki.common.exception

// "언제 다시 시도하면 되는지" 가 응답 계약의 일부인 예외가 구현한다(#339 아이템 등록 한도).
// GlobalExceptionHandler 가 이 값을 Retry-After 헤더에 delta-seconds 형식으로 싣는다(RFC 9110 §10.2.3).
//
// HttpMappable(status·category)과 분리해 둔 이유: 같은 예외 클래스의 대다수 사유는 재시도 시점을 모른다.
// 예외 클래스 전체에 nullable 필드를 다는 대신, 재시도 시점을 아는 예외만 이 인터페이스를 구현하고
// 핸들러가 `as?` 로 가려 헤더를 붙인다 — 헤더 유무가 타입으로 드러나고, 모르는 예외에 0 같은 거짓값이 안 실린다.
interface RetryAfter {
    val retryAfterSeconds: Long
}
