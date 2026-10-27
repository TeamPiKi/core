package com.depromeet.piki.wishlist.repository

import java.util.UUID

interface WishCardView {
    val userId: UUID
    val wishId: Long
}
