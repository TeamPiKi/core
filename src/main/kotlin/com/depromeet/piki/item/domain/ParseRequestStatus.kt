package com.depromeet.piki.item.domain

enum class ParseRequestStatus(
    val description: String,
) {
    PENDING("결과를 기다리는 중"),
    PROCESSING("스트림 전환 이전에 집힌 행에만 남은 값. 결과를 기다리는 중으로 다룸"),
    SUCCEEDED("추출 결과를 반영함"),
    FAILED("실패로 닫음"),
}
