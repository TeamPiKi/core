package com.depromeet.piki.item.domain

import com.depromeet.piki.common.domain.LongBaseEntity
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.domain.ProductLinkConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table

// 요청 한 건. 입력의 정본이고 쌓이기만 해서 요청자의 요청 이력이 된다.
@Entity
@Table(name = "item_parse_outbox")
class ItemParseOutbox(

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    val requesterType: ParseRequesterType,

    @Column(nullable = false)
    val requesterId: Long,

    @Convert(converter = ProductLinkConverter::class)
    @Column(length = ProductLink.MAX_LENGTH)
    val sourceUrl: ProductLink?,

    // 같은 URL 을 다시 요청했는지 가르는 데 쓴다. 리다이렉트를 따라가기 전 주소라 아이템의 정체 URL 과 다를 수 있다
    @Column(length = ProductLink.MAX_LENGTH)
    val sourceUrlWithoutTracking: String?,

    @Column(length = 255)
    val sourceImageKey: String?,

) : LongBaseEntity() {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: ItemParseOutboxStatus = ItemParseOutboxStatus.PENDING
        protected set

    init {
        require(listOfNotNull(sourceUrl, sourceImageKey).size == 1) { "아웃박스는 URL 과 이미지 키 중 하나만 든다" }
    }

    val requester: ParseRequester get() = ParseRequester(requesterType, requesterId)

    val input: ParseInput
        get() = sourceUrl?.let { ParseInput.Link(it) } ?: ParseInput.Image(requireNotNull(sourceImageKey))

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
        const val SOURCE_IMAGE_KEY_UNIQUE = "uk_item_parse_outbox_source_image_key"

        fun of(
            requester: ParseRequester,
            input: ParseInput,
        ): ItemParseOutbox =
            when (input) {
                is ParseInput.Link ->
                    ItemParseOutbox(
                        requesterType = requester.type,
                        requesterId = requester.id,
                        sourceUrl = input.link,
                        sourceUrlWithoutTracking = sourceUrlWithoutTrackingOf(input.link),
                        sourceImageKey = null,
                    )
                is ParseInput.Image ->
                    ItemParseOutbox(
                        requesterType = requester.type,
                        requesterId = requester.id,
                        sourceUrl = null,
                        sourceUrlWithoutTracking = null,
                        sourceImageKey = input.key,
                    )
            }

        // 저장 상한을 넘는 URL 은 중복 판정에서 빠진다
        fun sourceUrlWithoutTrackingOf(link: ProductLink): String? = CanonicalLink.of(link).takeUnless { it.exceedsStorageLimit }?.url
    }
}
