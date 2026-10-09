package com.depromeet.piki.item.domain

import com.depromeet.piki.contracts.extraction.v1.ExtractionMethod

enum class ItemSnapshotSource(
    val description: String,
) {
    SERVER("구조화 파서 추출"),
    SERVER_LLM("LLM 추출"),
    MANUAL("사용자 수기 입력"),
    ;

    companion object {
        // 모르는 추출 경로는 출처를 비운다. extractor 가 경로를 더해도 이 바이너리는 깨지지 않는다
        fun of(method: ExtractionMethod?): ItemSnapshotSource? =
            when (method) {
                ExtractionMethod.STRUCTURED -> SERVER
                ExtractionMethod.LLM -> SERVER_LLM
                ExtractionMethod.EXTRACTION_METHOD_UNSPECIFIED, ExtractionMethod.UNRECOGNIZED, null -> null
            }
    }
}
