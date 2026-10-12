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
}
