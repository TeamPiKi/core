package com.depromeet.piki.item.service

import com.depromeet.piki.item.domain.ItemParseOutbox
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.domain.ItemVersions
import com.depromeet.piki.item.domain.ParseInput
import com.depromeet.piki.product.domain.ProductLink
import java.time.LocalDateTime
import java.util.UUID

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

    companion object {
        fun current(
            versions: ItemVersions,
            viewer: UUID,
            refreshing: Boolean,
        ): ItemCard = Resolved(versions.displayFor(viewer), refreshing)

        // 시작한 토너먼트의 카드. 시작 시각의 값으로 고정돼 이후의 새로고침이 보이지 않는다
        fun fixedAt(
            startedAt: LocalDateTime,
            versions: ItemVersions,
            viewer: UUID,
        ): ItemCard = Resolved(versions.asOf(startedAt).displayFor(viewer), refreshing = false)
    }
}
