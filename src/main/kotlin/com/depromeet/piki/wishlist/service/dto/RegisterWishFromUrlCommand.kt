package com.depromeet.piki.wishlist.service.dto

import com.depromeet.piki.product.domain.ProductLink
import java.util.UUID

data class RegisterWishFromUrlCommand(
    val userId: UUID,
    val link: ProductLink,
    // 주면 담으면서 그 폴더에도 넣는다(#1266)
    val folderId: Long? = null,
)
