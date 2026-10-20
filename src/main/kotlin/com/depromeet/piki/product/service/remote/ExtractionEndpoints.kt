package com.depromeet.piki.product.service.remote

import com.depromeet.piki.contracts.extraction.v1.ExtractionProto

// 원격 추출 계약(shared-infra/contracts/extraction.proto)의 service 선언에서 경로와 호출자 타임아웃을 읽는다.
// rpc 이름이 계약에서 사라지면 클래스 초기화에서 터져 부팅이 실패한다 — 없는 경로로 조용히 호출하지 않는다.
internal object ExtractionEndpoints {
    private val service = ExtractionProto.getDescriptor().findServiceByName("ExtractionService")

    val LINK_EXTRACTION: String = path("LinkExtraction")
    val IMAGE_EXTRACTION: String = path("ImageExtraction")
    val MODEL_PROBE: String = path("ModelProbe")

    val CALLER_READ_TIMEOUT_MS: Int = service.options.getExtension(ExtractionProto.callerReadTimeoutSeconds) * 1_000

    private fun path(rpc: String): String = service.findMethodByName(rpc).options.getExtension(ExtractionProto.httpPost)
}
