package com.depromeet.piki.item.service

import com.depromeet.piki.common.exception.HttpMappable
import com.depromeet.piki.product.service.ExtractionFailureBucket
import com.depromeet.piki.product.service.ExtractionFailureCode
import com.depromeet.piki.product.service.ProductSnapshot

object ItemParsingMetrics {
    const val RESULT_READY = "ready"
    const val RESULT_INCOMPLETE = "incomplete"
    const val RESULT_FAILED = "failed"

    const val REASON_NONE = "none"

    // 값은 계약(shared-infra/contracts/extraction.proto)의 Bucket 이름을 소문자로 쓴 것. ExtractionErrorCatalogTest 가 대조한다
    const val REASON_NOT_PRODUCT = "not_product"
    const val REASON_UNREADABLE = "unreadable"
    const val REASON_BLOCKED = "blocked"
    const val REASON_EXTRACT_QUALITY = "extract_quality"
    const val REASON_INTERNAL_ERROR = "internal_error"

    const val REASON_READY_REJECTED = "ready_rejected"

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
