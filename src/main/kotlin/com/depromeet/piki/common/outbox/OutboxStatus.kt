package com.depromeet.piki.common.outbox

enum class OutboxStatus(
    val description: String,
) {
    PENDING("처리 전"),
    PROCESSING("처리 중"),
    SUCCEEDED("처리 성공"),
    FAILED("처리 실패"),
}
