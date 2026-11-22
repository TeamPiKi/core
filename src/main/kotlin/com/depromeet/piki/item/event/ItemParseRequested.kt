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
        const val STREAM = "parse:requests"

        fun of(request: ItemParseOutbox): ItemParseRequested = ItemParseRequested(request.getId(), request.inputType, request.inputValue)
    }
}
