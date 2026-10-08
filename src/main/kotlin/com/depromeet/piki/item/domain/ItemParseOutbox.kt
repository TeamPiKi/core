package com.depromeet.piki.item.domain

import com.depromeet.piki.common.domain.LongBaseEntity
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
    val requesterType: ParseRequesterType,

    @Column(nullable = false)
    val requesterId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val purpose: ParsePurpose,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val inputType: ParseInputType,

    @Column(nullable = false, length = ProductLink.MAX_LENGTH)
    val inputValue: String,

    // 같은 요청자의 중복 요청 판정 키. 링크는 추적 파라미터를 뗀 URL, 이미지는 키 그대로
    @Column(length = ProductLink.MAX_LENGTH)
    val canonicalInput: String?,

) : LongBaseEntity() {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: ItemParseOutboxStatus = ItemParseOutboxStatus.PENDING
        protected set

    val requester: ParseRequester get() = ParseRequester(requesterType, requesterId)

    val input: ParseInput get() = ParseInput(inputType, inputValue)

    fun isRefresh(): Boolean = purpose == ParsePurpose.REFRESH

    fun isProcessing(): Boolean = status == ItemParseOutboxStatus.PROCESSING

    fun isInProgress(): Boolean = status == ItemParseOutboxStatus.PENDING || status == ItemParseOutboxStatus.PROCESSING

    fun isFailed(): Boolean = status == ItemParseOutboxStatus.FAILED

    fun claim() = transit(from = ItemParseOutboxStatus.PENDING, to = ItemParseOutboxStatus.PROCESSING)

    fun requeue() = transit(from = ItemParseOutboxStatus.PROCESSING, to = ItemParseOutboxStatus.PENDING)

    fun succeed() = transit(from = ItemParseOutboxStatus.PROCESSING, to = ItemParseOutboxStatus.SUCCEEDED)

    fun fail() = transit(from = ItemParseOutboxStatus.PROCESSING, to = ItemParseOutboxStatus.FAILED)

    private fun transit(
        from: ItemParseOutboxStatus,
        to: ItemParseOutboxStatus,
    ) {
        check(status == from) { "item parse outbox ${getIdOrNull()} 은 $status 라 $to 로 갈 수 없다" }
        status = to
    }

    companion object {
        fun of(
            requester: ParseRequester,
            purpose: ParsePurpose,
            input: ParseInput,
        ): ItemParseOutbox =
            ItemParseOutbox(
                requesterType = requester.type,
                requesterId = requester.id,
                purpose = purpose,
                inputType = input.type,
                inputValue = input.value,
                canonicalInput = canonicalInputOf(input),
            )

        // 저장 상한을 넘는 URL 은 중복 판정에서 빠진다
        fun canonicalInputOf(input: ParseInput): String? {
            val link = input.linkOrNull() ?: return input.value
            return CanonicalLink.of(link).takeUnless { it.exceedsStorageLimit }?.url
        }
    }
}
