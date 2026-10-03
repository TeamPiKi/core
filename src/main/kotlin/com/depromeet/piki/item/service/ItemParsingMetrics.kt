package com.depromeet.piki.item.service

import com.depromeet.piki.common.exception.HttpMappable
import com.depromeet.piki.product.service.ExtractionFailureBucket
import com.depromeet.piki.product.service.ExtractionFailureCode
import com.depromeet.piki.product.service.ProductSnapshot
import io.micrometer.core.instrument.MeterRegistry

// 모든 경로가 같은 라벨 키를 쓴다. 키가 어긋나면 Prometheus 가 뒤 시계열을 조용히 버린다(#465).
// host 는 카디널리티가 무한이라 라벨에 넣지 않고 로그로 본다.
object ItemParsingMetrics {
    private const val METRIC = "item.parsing"
    private const val TAG_RESULT = "result"
    private const val TAG_REASON = "reason"

    const val RESULT_READY = "ready"
    const val RESULT_INCOMPLETE = "incomplete"
    const val RESULT_FAILED = "failed"

    const val REASON_NONE = "none"

    // 값은 infra 의 extraction-error-codes.yaml bucket 과 같아야 한다. ExtractionErrorCatalogTest 가 대조한다.
    const val REASON_NOT_PRODUCT = "not_product"
    const val REASON_UNREADABLE = "unreadable"
    const val REASON_BLOCKED = "blocked"
    const val REASON_EXTRACT_QUALITY = "extract_quality"
    const val REASON_INTERNAL_ERROR = "internal_error"

    const val REASON_READY_REJECTED = "ready_rejected"

    fun record(
        registry: MeterRegistry,
        result: String,
        reason: String,
    ) {
        registry.counter(METRIC, TAG_RESULT, result, TAG_REASON, reason).increment()
    }

    fun missingFieldsOf(extracted: ProductSnapshot): String {
        val missing = mutableListOf<String>()
        extracted.name?.takeIf { it.isNotBlank() } ?: missing.add("name")
        extracted.price ?: missing.add("price")
        extracted.imageUrl ?: missing.add("imageUrl")
        return missing.joinToString("+")
    }

    fun failureReasonOf(e: Throwable): ParseFailureReason {
        val bucket = ((e as? HttpMappable)?.errorCode as? ExtractionFailureCode)?.bucket ?: return ParseFailureReason.INTERNAL_ERROR
        return when (bucket) {
            ExtractionFailureBucket.NOT_PRODUCT -> ParseFailureReason.NOT_PRODUCT
            ExtractionFailureBucket.UNREADABLE -> ParseFailureReason.UNREADABLE
            ExtractionFailureBucket.BLOCKED -> ParseFailureReason.BLOCKED
            ExtractionFailureBucket.EXTRACT_QUALITY -> ParseFailureReason.EXTRACT_QUALITY
            ExtractionFailureBucket.INTERNAL_ERROR -> ParseFailureReason.INTERNAL_ERROR
        }
    }
}
