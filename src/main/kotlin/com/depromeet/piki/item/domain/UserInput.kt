package com.depromeet.piki.item.domain

data class UserInput(
    val name: String?,
    val price: Int?,
    val imageUrl: String?,
    val currency: String?,
) {
    fun filledFrom(base: ItemSnapshot): UserInput =
        UserInput(
            name = name ?: base.name,
            price = price ?: base.price,
            imageUrl = imageUrl ?: base.imageUrl,
            currency = currency ?: base.currency,
        )

    companion object {
        // 올리기 전이라 이미지 URL 이 없어 자리만 채운다. 검증에만 쓰고 저장하지 않는다
        private const val PENDING_UPLOAD_IMAGE_URL = "https://validation.invalid/pre-upload.png"

        fun beforeImageUpload(
            name: String?,
            price: Int?,
            currency: String?,
        ): UserInput = UserInput(name = name, price = price, imageUrl = PENDING_UPLOAD_IMAGE_URL, currency = currency)
    }
}
