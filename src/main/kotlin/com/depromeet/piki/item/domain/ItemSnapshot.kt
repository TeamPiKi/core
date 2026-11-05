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

// 파싱 성공·사용자 입력 때만 생기고 바뀌지 않는다
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

    // 할인율은 저장하지 않고 price 와 함께 계산한다(DiscountRate). 정가와 할인율을 둘 다 두면 어긋날 수 있다
    @Column
    val originalPrice: Int?,

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
        require(filledFieldCount(name, price, imageUrl) > 0) { "값이 하나도 없는 버전은 만들 수 없다" }
        require(fitsColumns(name, imageUrl, currency)) { "칸 길이를 넘는 값이다" }
        require((price ?: 0) >= 0) { "price 는 음수일 수 없다: $price" }
        require(originalPriceConsistent(price, originalPrice)) { "originalPrice 가 price 와 안 맞는다: originalPrice=$originalPrice price=$price" }
    }

    fun isReady(): Boolean = filledFieldCount() == READY_FIELD_COUNT

    fun isIncomplete(): Boolean = !isReady()

    fun isOwnedBy(user: UUID): Boolean = createdBy == user

    private fun filledFieldCount(): Int = filledFieldCount(name, price, imageUrl)

    companion object {
        const val NAME_MAX_LENGTH = 512
        const val IMAGE_URL_MAX_LENGTH = 2048
        const val CURRENCY_MAX_LENGTH = 8
        private const val READY_FIELD_COUNT = 3

        // 추출 결과가 버전이 될 수 있는지. 외부 계약 케이스라 예외가 아니라 판정으로 걸러 호출부가 파싱 실패로 닫는다
        fun accepts(extracted: ProductSnapshot): Boolean =
            filledFieldCount(extracted.name, extracted.price, extracted.imageUrl) > 0 &&
                fitsColumns(extracted.name, extracted.imageUrl, extracted.currency)

        // 통화는 단독으로 카드 값이 되지 못해 세지 않는다
        private fun filledFieldCount(
            name: String?,
            price: Int?,
            imageUrl: String?,
        ): Int = listOfNotNull(name?.takeIf { it.isNotBlank() }, price, imageUrl).size

        // 정가만 있고 현재가가 없거나 현재가보다 낮으면 할인율이 뜻을 잃는다. 입력 경계가 걸러 여기 닿으면 버그다
        private fun originalPriceConsistent(
            price: Int?,
            originalPrice: Int?,
        ): Boolean {
            originalPrice ?: return true
            price ?: return false
            return originalPrice >= 0 && originalPrice >= price
        }

        private fun fitsColumns(
            name: String?,
            imageUrl: String?,
            currency: String?,
        ): Boolean =
            (name?.length ?: 0) <= NAME_MAX_LENGTH &&
                (imageUrl?.length ?: 0) <= IMAGE_URL_MAX_LENGTH &&
                (currency?.length ?: 0) <= CURRENCY_MAX_LENGTH

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
                originalPrice = extracted.originalPrice,
                currency = extracted.currency,
                method = extracted.method,
                userInput = false,
                createdBy = createdBy,
            )

        fun userInput(
            itemId: Long,
            values: UserInputValues,
            createdBy: UUID,
        ): ItemSnapshot =
            ItemSnapshot(
                itemId = itemId,
                name = values.name,
                imageUrl = values.imageUrl,
                price = values.price,
                originalPrice = values.originalPrice,
                currency = values.currency,
                method = null,
                userInput = true,
                createdBy = createdBy,
            )
    }
}
