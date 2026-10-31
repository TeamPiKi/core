package com.depromeet.piki.item.domain

enum class RecoveryOutcome(
    val description: String,
) {
    REQUEUED("대기로 되돌려 다시 파싱"),
    EXHAUSTED("회수 상한에 닿아 실패로 닫음"),
}
