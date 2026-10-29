package com.depromeet.piki.wishlist.service.dto

import com.depromeet.piki.product.domain.ProductLink
import java.util.UUID

data class RegisterWishFromUrlCommand(
    val userId: UUID,
    val link: ProductLink,
)
