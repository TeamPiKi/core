package com.depromeet.piki.item.domain

import com.depromeet.piki.common.domain.LongBaseEntity
import com.depromeet.piki.product.domain.ParseInputType
import com.depromeet.piki.product.domain.ProductLink
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table

@Entity
@Table(name = "item_parse_outbox")
class ItemParseOutbox(

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    val slotType: ItemSlotType,

    @Column(nullable = false)
    val slotId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val purpose: ParsePurpose,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val inputType: ParseInputType,

    @Column(nullable = false, length = ProductLink.MAX_LENGTH)
    val inputValue: String,

    // 같은 슬롯의 중복 요청 판정 키. 귀결점을 따라가기 전의 값이라 아이템의 정체 값과 다를 수 있다
    @Column(length = ProductLink.MAX_LENGTH)
    val normalizedInput: NormalizedInput?,

) : LongBaseEntity() {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: ParseRequestStatus = ParseRequestStatus.PENDING
        protected set

    val slot: ItemSlot get() = ItemSlot(slotType, slotId)

    val input: ParseInput get() = ParseInput.of(inputType, inputValue)

    fun isRefresh(): Boolean = purpose == ParsePurpose.REFRESH

    fun isInProgress(): Boolean = status == ParseRequestStatus.PENDING || status == ParseRequestStatus.PROCESSING

    fun isFailed(): Boolean = status == ParseRequestStatus.FAILED

    fun succeed() = close(ParseRequestStatus.SUCCEEDED)

    fun fail() = close(ParseRequestStatus.FAILED)

    private fun close(to: ParseRequestStatus) {
        check(isInProgress()) { "파싱 요청 ${getIdOrNull()} 은 $status 라 $to 로 갈 수 없다" }
        status = to
    }

    companion object {
        fun of(
            slot: ItemSlot,
            purpose: ParsePurpose,
            input: ParseInput,
        ): ItemParseOutbox =
            ItemParseOutbox(
                slotType = slot.type,
                slotId = slot.id,
                purpose = purpose,
                inputType = input.type,
                inputValue = input.value,
                normalizedInput = NormalizedInput.of(input),
            )
    }
}
