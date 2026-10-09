package com.depromeet.piki.item.domain

import com.depromeet.piki.common.domain.LongBaseEntity
import com.depromeet.piki.contracts.extraction.v1.ExtractionMethod
import com.depromeet.piki.product.service.ProductSnapshot
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.util.UUID

// 파싱 성공·수동 보정 때만 생기고 바뀌지 않는다
@Entity
@Table(name = "item_snapshots")
class ItemSnapshot(

    @Column(nullable = false, updatable = false)
    val itemId: Long,

    @Column(length = NAME_MAX_LENGTH)
    val name: String?,

    @Column(length = IMAGE_URL_MAX_LENGTH)
    val imageUrl: String?,

    @Column
    val price: Int?,

    @Column(length = CURRENCY_MAX_LENGTH)
    val currency: String?,

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    val method: ExtractionMethod?,

    @Column(nullable = false)
    val userInput: Boolean,

    @Column(nullable = false, columnDefinition = "BINARY(16)")
    val createdBy: UUID,

) : LongBaseEntity() {
    init {
        require(filledFieldCount() > 0) { "값이 하나도 없는 버전은 만들 수 없다" }
        require((price ?: 0) >= 0) { "price 는 음수일 수 없다: $price" }
        require((name?.length ?: 0) <= NAME_MAX_LENGTH) { "name 길이가 ${NAME_MAX_LENGTH}자를 초과했다" }
        require((imageUrl?.length ?: 0) <= IMAGE_URL_MAX_LENGTH) { "imageUrl 길이가 ${IMAGE_URL_MAX_LENGTH}자를 초과했다" }
        require((currency?.length ?: 0) <= CURRENCY_MAX_LENGTH) { "currency 길이가 ${CURRENCY_MAX_LENGTH}자를 초과했다" }
    }

    fun isReady(): Boolean = filledFieldCount() == READY_FIELD_COUNT

    fun isIncomplete(): Boolean = !isReady()

    fun isOwnedBy(user: UUID): Boolean = createdBy == user

    // 추출 경로를 모르는 옛 행도 공유값이다. 만든 사람으로 가르면 추정 백필된 옛 값이 카드에서 사라진다
    fun isSharedValue(): Boolean = isReady() && !userInput

    // 통화는 단독으로 카드 값이 되지 못해 세지 않는다
    private fun filledFieldCount(): Int = listOfNotNull(name?.takeIf { it.isNotBlank() }, price, imageUrl).size

    companion object {
        const val NAME_MAX_LENGTH = 512
        const val IMAGE_URL_MAX_LENGTH = 2048
        const val CURRENCY_MAX_LENGTH = 8
        private const val READY_FIELD_COUNT = 3

        // 값이 하나도 없거나 칸 길이를 넘는 추출 결과는 버전이 되지 못한다. 호출부가 파싱 실패로 닫는다
        fun accepts(extracted: ProductSnapshot): Boolean =
            listOfNotNull(extracted.name?.takeIf { it.isNotBlank() }, extracted.price, extracted.imageUrl).isNotEmpty() &&
                (extracted.name?.length ?: 0) <= NAME_MAX_LENGTH &&
                (extracted.imageUrl?.length ?: 0) <= IMAGE_URL_MAX_LENGTH

        fun extracted(
            itemId: Long,
            extracted: ProductSnapshot,
            createdBy: UUID,
        ): ItemSnapshot =
            ItemSnapshot(
                itemId = itemId,
                name = extracted.name,
                imageUrl = extracted.imageUrl,
                price = extracted.price,
                currency = extracted.currency,
                method = extracted.method,
                userInput = false,
                createdBy = createdBy,
            )

        fun manual(
            itemId: Long,
            values: ManualValues,
            createdBy: UUID,
        ): ItemSnapshot =
            ItemSnapshot(
                itemId = itemId,
                name = values.name,
                imageUrl = values.imageUrl,
                price = values.price,
                currency = values.currency,
                method = null,
                userInput = true,
                createdBy = createdBy,
            )
    }
}
