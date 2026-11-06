package com.depromeet.piki.item.domain

data class UserInput(
    val name: String?,
    val price: Int?,
    val imageUrl: String?,
    val currency: String?,
    val originalPrice: Int?,
) {
    fun isEmpty(): Boolean =
        listOfNotNull(
            name?.takeIf { it.isNotBlank() },
            price,
            imageUrl?.takeIf { it.isNotBlank() },
            currency?.takeIf { it.isNotBlank() },
            originalPrice,
        ).isEmpty()

    fun filledFrom(base: ItemSnapshot): UserInput {
        val mergedPrice = price ?: base.price
        return UserInput(
            name = name ?: base.name,
            price = mergedPrice,
            imageUrl = imageUrl ?: base.imageUrl,
            currency = currency ?: base.currency,
            originalPrice = originalPrice ?: carriedOriginalPrice(base.originalPrice, mergedPrice),
        )
    }

    // 가격만 올려 적으면 이어 쓰던 정가가 그보다 낮아진다. 사용자가 적지 않은 값으로 거절하지 않고 정가를 모름으로 되돌린다
    private fun carriedOriginalPrice(
        baseOriginalPrice: Int?,
        mergedPrice: Int?,
    ): Int? {
        baseOriginalPrice ?: return null
        mergedPrice ?: return null
        return baseOriginalPrice.takeIf { it >= mergedPrice }
    }

    companion object {
        // 올리기 전이라 이미지 URL 이 없어 자리만 채운다. 검증에만 쓰고 저장하지 않는다
        private const val PENDING_UPLOAD_IMAGE_URL = "https://validation.invalid/pre-upload.png"

        fun beforeImageUpload(
            name: String?,
            price: Int?,
            originalPrice: Int?,
            currency: String?,
        ): UserInput =
            UserInput(name = name, price = price, imageUrl = PENDING_UPLOAD_IMAGE_URL, currency = currency, originalPrice = originalPrice)
    }
}
