package com.depromeet.piki.wishlist.service.dto

import com.depromeet.piki.wishlist.domain.WishCursor
import com.depromeet.piki.wishlist.domain.WishSearchQuery
import com.depromeet.piki.wishlist.domain.WishlistSize
import java.util.UUID

data class GetWishlistQuery(
    val userId: UUID,
    val cursor: WishCursor?,
    val size: WishlistSize,
    // 그 폴더에 든 위시만, 넣은 순서로
    val folderId: Long? = null,
    // 그 폴더에 없는 위시만
    val excludeFolderId: Long? = null,
    val search: WishSearchQuery? = null,
)
