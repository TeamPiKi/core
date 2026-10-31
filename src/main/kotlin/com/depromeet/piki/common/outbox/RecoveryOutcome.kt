package com.depromeet.piki.common.outbox

enum class RecoveryOutcome(
    val description: String,
) {
    REQUEUED("대기로 되돌려 다시 처리"),
    EXHAUSTED("회수 상한에 닿아 실패로 닫음"),
}
