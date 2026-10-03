package com.depromeet.piki.item.service

enum class ParseFailureReason(
    val metricLabel: String,
    val description: String,
) {
    NOT_PRODUCT(ItemParsingMetrics.REASON_NOT_PRODUCT, "사용자가 상품이 아닌 것을 넣음"),
    UNREADABLE(ItemParsingMetrics.REASON_UNREADABLE, "우리 구성으로 페이지를 읽지 못함"),
    BLOCKED(ItemParsingMetrics.REASON_BLOCKED, "대상 사이트가 우리를 막음"),
    EXTRACT_QUALITY(ItemParsingMetrics.REASON_EXTRACT_QUALITY, "추출은 됐지만 값을 믿을 수 없거나 하나도 얻지 못함"),
    INTERNAL_ERROR(ItemParsingMetrics.REASON_INTERNAL_ERROR, "우리 버그이거나 분류되지 않은 실패"),
    READY_REJECTED(ItemParsingMetrics.REASON_READY_REJECTED, "추출값이 버전 검증에 막힘"),
}
