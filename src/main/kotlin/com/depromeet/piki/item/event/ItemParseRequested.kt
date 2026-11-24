package com.depromeet.piki.item.event

import com.depromeet.piki.item.domain.ItemParseOutbox
import com.depromeet.piki.product.domain.ParseInputType
import org.springframework.modulith.events.Externalized

@Externalized(ItemParseRequested.STREAM)
data class ItemParseRequested(
    val requestId: Long,
    val inputType: ParseInputType,
    val inputValue: String,
) {
    companion object {
        // 어노테이션 인자라 계약에서 읽지 못함. ParseStreamContractTest 가 계약의 값과 대조
        const val STREAM = "parse:requests"

        fun of(request: ItemParseOutbox): ItemParseRequested = ItemParseRequested(request.getId(), request.inputType, request.inputValue)
    }
}
