package com.depromeet.piki.wishlist.service.dto

import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.service.ItemCard
import com.depromeet.piki.wishlist.domain.Wish

// history 는 표시값 규칙(내 입력 존중, 남의 입력 무시)을 타지 않아 첫 항목이 card 의 값과 다를 수 있다
data class WishDetail(
    val wish: Wish,
    val item: Item?,
    val card: ItemCard,
    val history: List<ItemSnapshot>,
    val sourcePlatform: String?,
)
