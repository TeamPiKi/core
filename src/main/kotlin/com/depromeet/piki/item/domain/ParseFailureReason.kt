package com.depromeet.piki.item.domain

// 워커의 확정 실패 세부(not_product·blocked 등)는 로그·메트릭이 들고 있어 EXTRACTION 하나로 접는다.
enum class ParseFailureReason(
    val description: String,
) {
    EXTRACTION("워커가 실행했으나 결과가 없거나 확정 실패"),
    RETRY_EXHAUSTED("실행 예산(MAX_ATTEMPTS) 소진"),
    NO_SOURCE("입력(link·imageKey) 부재로 실행 불가"),
    DEADLINE("마감(DEADLINE_MINUTES) 초과"),
}
