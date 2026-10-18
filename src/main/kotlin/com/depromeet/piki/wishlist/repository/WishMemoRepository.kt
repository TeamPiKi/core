package com.depromeet.piki.wishlist.repository

import com.depromeet.piki.wishlist.domain.WishMemo
import java.util.UUID

interface WishMemoRepository {
    fun save(memo: WishMemo): WishMemo

    fun saveAll(memos: List<WishMemo>): List<WishMemo>

    fun findCurrentByWishId(wishId: Long): WishMemo?

    fun findAllByWishId(wishId: Long): List<WishMemo>

    // 위시를 지우기 전에 불러야 한다. 메모는 위시로만 찾는다
    fun hardDeleteAllByUserId(userId: UUID): Int
}
