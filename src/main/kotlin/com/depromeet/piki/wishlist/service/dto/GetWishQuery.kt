package com.depromeet.piki.wishlist.service.dto

import java.util.UUID

data class GetWishQuery(
    val userId: UUID,
    val wishId: Long,
)
