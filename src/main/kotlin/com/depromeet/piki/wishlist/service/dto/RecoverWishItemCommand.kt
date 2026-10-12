package com.depromeet.piki.wishlist.service.dto

import com.depromeet.piki.image.domain.ProductImage
import com.depromeet.piki.item.domain.UserInput
import java.util.UUID

data class RecoverWishItemCommand(
    val userId: UUID,
    val wishId: Long,
    val name: String?,
    val price: Int?,
    val currency: String?,
    val image: ProductImage?,
    val memo: String?,
) {
    fun memoOnly(): UpdateWishMemoCommand? {
        if (listOfNotNull(name, price, currency, image).isNotEmpty()) return null
        return memo?.let { UpdateWishMemoCommand(userId = userId, wishId = wishId, memo = it) }
    }

    fun userInput(imageUrl: String?): UserInput = UserInput(name = name, price = price, imageUrl = imageUrl, currency = currency)

    fun toApplyUserInput(imageUrl: String?): ApplyWishUserInputCommand =
        ApplyWishUserInputCommand(
            userId = userId,
            wishId = wishId,
            userInput = userInput(imageUrl),
            memo = memo,
        )
}
