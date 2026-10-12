package com.depromeet.piki.item.domain

// 사용자 입력 버전은 READY 여야 해서 카드 값과 합친 뒤에도 이름·가격·이미지가 다 있어야 한다
data class UserInputValues(
    val name: String,
    val price: Int,
    val imageUrl: String,
    val currency: String?,
) {
    companion object {
        fun merge(
            base: ItemSnapshot?,
            input: UserInput,
        ): UserInputValues {
            val mergedName = input.name ?: base?.name
            if (mergedName.isNullOrBlank()) throw ItemException.nameRequiredForReady()
            return UserInputValues(
                name = mergedName,
                price = input.price ?: base?.price ?: throw ItemException.priceRequiredForReady(),
                imageUrl = input.imageUrl ?: base?.imageUrl ?: throw ItemException.imageRequiredForReady(),
                currency = input.currency ?: base?.currency,
            )
        }
    }
}
