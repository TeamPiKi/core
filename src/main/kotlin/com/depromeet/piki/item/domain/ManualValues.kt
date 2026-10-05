package com.depromeet.piki.item.domain

// 카드가 보여주던 값 위에 입력을 합친다. 수기 보정은 READY 를 만들어야 해서 이름·가격·이미지가 다 있어야 한다
data class ManualValues(
    val name: String,
    val price: Int,
    val imageUrl: String,
    val currency: String?,
) {
    companion object {
        fun merge(
            base: ItemSnapshot?,
            name: String?,
            price: Int?,
            imageUrl: String?,
            currency: String?,
        ): ManualValues {
            val mergedName = name ?: base?.name
            if (mergedName.isNullOrBlank()) throw ItemException.nameRequiredForReady()
            return ManualValues(
                name = mergedName,
                price = price ?: base?.price ?: throw ItemException.priceRequiredForReady(),
                imageUrl = imageUrl ?: base?.imageUrl ?: throw ItemException.imageRequiredForReady(),
                currency = currency ?: base?.currency,
            )
        }
    }
}
