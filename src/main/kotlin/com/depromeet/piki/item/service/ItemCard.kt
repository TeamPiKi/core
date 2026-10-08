package com.depromeet.piki.item.service

import com.depromeet.piki.item.domain.ItemParseOutbox
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.domain.ParseInput
import com.depromeet.piki.product.domain.ProductLink

sealed interface ItemCard {
    val status: ItemStatus
    val display: ItemSnapshot?
    val refreshing: Boolean
    val pendingLink: ProductLink?

    val itemId: Long? get() = display?.itemId

    fun isReady(): Boolean = status == ItemStatus.READY

    fun isIncomplete(): Boolean = status == ItemStatus.INCOMPLETE

    data class Unresolved(
        private val latestRequest: ItemParseOutbox,
    ) : ItemCard {
        override val status: ItemStatus get() = if (latestRequest.isFailed()) ItemStatus.FAILED else ItemStatus.PARSING
        override val display: ItemSnapshot? get() = null
        override val refreshing: Boolean get() = false
        override val pendingLink: ProductLink? get() = (latestRequest.input as? ParseInput.Link)?.link
    }

    data class Resolved(
        override val display: ItemSnapshot,
        override val refreshing: Boolean,
    ) : ItemCard {
        override val status: ItemStatus get() = if (display.isReady()) ItemStatus.READY else ItemStatus.INCOMPLETE
        override val pendingLink: ProductLink? get() = null
    }
}
