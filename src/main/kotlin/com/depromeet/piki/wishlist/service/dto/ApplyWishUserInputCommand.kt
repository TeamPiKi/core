package com.depromeet.piki.wishlist.service.dto

import com.depromeet.piki.item.domain.UserInput
import java.util.UUID

data class ApplyWishUserInputCommand(
    val userId: UUID,
    val wishId: Long,
    val userInput: UserInput,
    val memo: String?,
)
