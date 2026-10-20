package com.depromeet.piki.wishlist.service.dto

import com.depromeet.piki.image.domain.ProductImageKey
import java.util.UUID

data class ConfirmWishImageRegistrationCommand(
    val userId: UUID,
    val imageKeys: List<ProductImageKey>,
)
