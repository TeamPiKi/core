package com.depromeet.piki.item.domain

// 사용자 입력 버전은 READY 여야 해서 이름·가격·이미지가 다 있어야 한다
data class UserInputValues(
    val name: String,
    val price: Int,
    val imageUrl: String,
    val currency: String?,
) {
    companion object {
        fun of(input: UserInput): UserInputValues {
            val name = input.name
            if (name.isNullOrBlank()) throw ItemException.nameRequiredForReady()
            return UserInputValues(
                name = name,
                price = input.price ?: throw ItemException.priceRequiredForReady(),
                imageUrl = input.imageUrl ?: throw ItemException.imageRequiredForReady(),
                currency = input.currency,
            )
        }
    }
}
