package com.depromeet.piki.item.service

import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.product.service.ProductSnapshot

sealed interface ParsingOutcome {
    fun isApplied(): Boolean

    fun extractedValue(): ProductSnapshot?

    data class Settled(
        val extracted: ProductSnapshot,
        val status: ItemStatus,
    ) : ParsingOutcome {
        override fun isApplied(): Boolean = true

        override fun extractedValue(): ProductSnapshot? = extracted.takeIf { status != ItemStatus.FAILED }
    }

    data object Failed : ParsingOutcome {
        override fun isApplied(): Boolean = true

        override fun extractedValue(): ProductSnapshot? = null
    }

    // 이 실행의 결과가 DB 에 남지 않음(쓰기 실패, 또는 다른 실행이 이미 끝낸 행)
    data object NotApplied : ParsingOutcome {
        override fun isApplied(): Boolean = false

        override fun extractedValue(): ProductSnapshot? = null
    }
}
