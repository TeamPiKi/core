package com.depromeet.piki.item.domain

enum class ItemParseOutboxStatus(
    val description: String,
) {
    PENDING("파싱 전"),
    PROCESSING("파싱 진행 중"),
    SUCCEEDED("파싱 성공. INCOMPLETE 포함"),
    FAILED("파싱 실패"),
}
