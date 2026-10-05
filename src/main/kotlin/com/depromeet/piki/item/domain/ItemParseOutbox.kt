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

    @Column(length = ProductLink.MAX_LENGTH)
    val urlWithoutTracking: String?,

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
        // 같은 이미지의 중복 확정을 막는 유니크. 이미지 입력일 때만 값이 생기는 DB 생성 컬럼(image_key)에 걸려 있다
        const val IMAGE_KEY_UNIQUE = "uk_item_parse_outbox_image_key"

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
                urlWithoutTracking = input.linkOrNull()?.let(::urlWithoutTrackingOf),
            )

        // 저장 상한을 넘는 URL 은 중복 판정에서 빠진다
        fun urlWithoutTrackingOf(link: ProductLink): String? = CanonicalLink.of(link).takeUnless { it.exceedsStorageLimit }?.url
    }
}
