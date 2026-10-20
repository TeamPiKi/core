package com.depromeet.piki.item.service

import com.depromeet.piki.item.domain.ItemException
import com.depromeet.piki.item.domain.ItemParseOutbox
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.domain.ParseInput
import com.depromeet.piki.item.domain.UserInput
import com.depromeet.piki.item.domain.UserInputValues
import com.depromeet.piki.product.domain.ProductLink

sealed interface ItemCard {
    val status: ItemStatus
    val display: ItemSnapshot?
    val refreshing: Boolean
    val pendingLink: ProductLink?

    val itemId: Long? get() = display?.itemId

    fun isReady(): Boolean = status == ItemStatus.READY

    fun isIncomplete(): Boolean = status == ItemStatus.INCOMPLETE

    fun isParsing(): Boolean = status == ItemStatus.PARSING || refreshing

    fun userInputValues(input: UserInput): UserInputValues {
        // 빈 입력을 받으면 지금 보이는 값을 복제한 사용자 입력 버전이 쌓여 가격 이력과 출처 표시가 바뀐다
        if (input.isEmpty()) throw ItemException.emptyUserInput()
        if (isParsing()) throw ItemException.parsingInProgress()
        return when (this) {
            is Unresolved -> UserInputValues.of(input)
            is Resolved -> UserInputValues.of(input.filledFrom(display))
        }
    }

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
