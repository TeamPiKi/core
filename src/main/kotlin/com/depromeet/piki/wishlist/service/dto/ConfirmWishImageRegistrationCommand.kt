package com.depromeet.piki.wishlist.service.dto

import com.depromeet.piki.image.domain.ProductImageKey
import java.util.UUID

data class ConfirmWishImageRegistrationCommand(
    val userId: UUID,
    val imageKeys: List<ProductImageKey>,
    // 주면 담으면서 그 폴더에도 넣는다(#1266)
    val folderId: Long? = null,
)
