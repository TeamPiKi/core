package com.depromeet.piki.item.domain

// INCOMPLETE 가 없는 이유: 값의 완성도는 결과 버전(ItemSnapshot)의 축이고 요청은 작업의 진행만 답한다.
enum class ParseRequestStatus(
    val description: String,
) {
    PENDING("작업 큐 적재. 디스패처의 집기 대기"),
    PROCESSING("디스패처가 집음. 워커 실행 중이거나 실행 진입 대기"),
    SUCCEEDED("워커가 결과 버전을 남기고 종결"),
    FAILED("결과 없이 종결. 사유는 failureReason"),
}
