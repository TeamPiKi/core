package com.depromeet.piki.item.domain

import com.depromeet.piki.common.domain.LongBaseEntity
import com.depromeet.piki.product.service.ProductSnapshot
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "item_snapshots")
class ItemSnapshot(
    @Column(nullable = false, updatable = false)
    val itemId: Long,
    name: String? = null,
    imageUrl: String? = null,
    price: Int? = null,
    currency: String? = null,
    status: ItemStatus = ItemStatus.PENDING,
    extractedAt: LocalDateTime? = null,
    source: ItemSnapshotSource? = null,
    createdBy: UUID? = null,
) : LongBaseEntity() {
    @Column(columnDefinition = "BINARY(16)")
    var createdBy: UUID? = createdBy
        protected set

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    var source: ItemSnapshotSource? = source
        protected set

    @Column(length = 512)
    var name: String? = name
        protected set

    @Column(length = 2048)
    var imageUrl: String? = imageUrl
        protected set

    var price: Int? = price
        protected set

    @Column(length = 8)
    var currency: String? = currency
        protected set

    // TODO: #1176 2단계에서 삭제 예정. 진행 상태는 ItemParseOutboxStatus 가 정본이고 버전은 성공 시에만 생김
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: ItemStatus = status
        protected set

    var extractedAt: LocalDateTime? = extractedAt
        protected set

    // JPA 가 빈 인스턴스로 이 블록을 먼저 실행하므로 필수값·상태 의존 검사는 둘 수 없다.
    init {
        validate(name, price, imageUrl, currency)
    }

    fun markExtracted(extracted: ProductSnapshot): ItemStatus {
        check(status == ItemStatus.PENDING) { "PENDING 이 아닌 snapshot(status=$status)은 추출 결과로 전이할 수 없다" }
        fillValues(extracted.name, extracted.price, extracted.imageUrl, extracted.currency)
        source = ItemSnapshotSource.fromWireMethod(extracted.extractionMethod)
        status = extractedStatus()
        if (hasValue()) extractedAt = LocalDateTime.now()
        return status
    }

    fun markFailed() {
        check(status == ItemStatus.PENDING) { "PENDING 이 아닌 snapshot(status=$status)은 FAILED 로 전이할 수 없다" }
        status = ItemStatus.FAILED
    }

    fun isReady(): Boolean = status == ItemStatus.READY

    fun isIncomplete(): Boolean = status == ItemStatus.INCOMPLETE

    fun isFailed(): Boolean = status == ItemStatus.FAILED

    fun isInProgress(): Boolean = status == ItemStatus.PENDING || status == ItemStatus.PROCESSING

    fun hasValue(): Boolean = isReady() || isIncomplete()

    fun isUnresolved(): Boolean = isFailed() || isIncomplete()

    fun isManual(): Boolean = source == ItemSnapshotSource.MANUAL

    fun isOwnedBy(user: UUID): Boolean = createdBy == user

    // 출처를 모르는 옛 행도 공유값이다. 만든 사람으로 가르면 추정 백필된 옛 값이 카드에서 사라진다.
    fun isSharedValue(): Boolean = isReady() && !isManual()

    private fun extractedStatus(): ItemStatus =
        when (extractedFields().size) {
            0 -> ItemStatus.FAILED
            READY_FIELD_COUNT -> ItemStatus.READY
            else -> ItemStatus.INCOMPLETE
        }

    // 통화는 단독으로 카드 값이 되지 못해 세지 않는다.
    private fun extractedFields(): List<Any> = listOfNotNull(name?.takeIf { it.isNotBlank() }, price, imageUrl)

    private fun fillValues(
        name: String?,
        price: Int?,
        imageUrl: String?,
        currency: String?,
    ) {
        val newName = name ?: this.name
        val newPrice = price ?: this.price
        val newImageUrl = imageUrl ?: this.imageUrl
        val newCurrency = currency ?: this.currency
        validate(newName, newPrice, newImageUrl, newCurrency)
        this.name = newName
        this.price = newPrice
        this.imageUrl = newImageUrl
        this.currency = newCurrency
    }

    private fun validate(
        name: String?,
        price: Int?,
        imageUrl: String?,
        currency: String?,
    ) {
        require((price ?: 0) >= 0) { "price 는 음수일 수 없다: $price" }
        require((name?.length ?: 0) <= NAME_MAX_LENGTH) { "name 길이가 ${NAME_MAX_LENGTH}자를 초과했다" }
        require((imageUrl?.length ?: 0) <= IMAGE_URL_MAX_LENGTH) { "imageUrl 길이가 ${IMAGE_URL_MAX_LENGTH}자를 초과했다" }
        require((currency?.length ?: 0) <= CURRENCY_MAX_LENGTH) { "currency 길이가 ${CURRENCY_MAX_LENGTH}자를 초과했다" }
    }

    companion object {
        const val NAME_MAX_LENGTH = 512
        const val IMAGE_URL_MAX_LENGTH = 2048
        const val CURRENCY_MAX_LENGTH = 8
        private const val READY_FIELD_COUNT = 3

        fun pending(
            itemId: Long,
            requestedBy: UUID,
        ): ItemSnapshot = ItemSnapshot(itemId = itemId, createdBy = requestedBy)

        fun manual(
            base: ItemSnapshot,
            name: String?,
            price: Int?,
            imageUrl: String?,
            currency: String?,
            createdBy: UUID,
        ): ItemSnapshot {
            val mergedName = name ?: base.name
            val mergedPrice = price ?: base.price
            val mergedImage = imageUrl ?: base.imageUrl
            if (mergedName.isNullOrBlank()) throw ItemException.nameRequiredForReady()
            mergedPrice ?: throw ItemException.priceRequiredForReady()
            mergedImage ?: throw ItemException.imageRequiredForReady()
            return ItemSnapshot(
                itemId = base.itemId,
                name = mergedName,
                imageUrl = mergedImage,
                price = mergedPrice,
                currency = currency ?: base.currency,
                status = ItemStatus.READY,
                extractedAt = LocalDateTime.now(),
                source = ItemSnapshotSource.MANUAL,
                createdBy = createdBy,
            )
        }
    }
}
