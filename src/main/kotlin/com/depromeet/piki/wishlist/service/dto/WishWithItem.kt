package com.depromeet.piki.wishlist.service.dto

import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.wishlist.domain.Wish

// wish 기록과 그 wish 가 가리키는 상품의 정체성(item)·활성 버전(snapshot) 묶음. 등록 결과·조회 항목이 공유한다.
// 표시값(name/price/image/status)은 snapshot 에서, 정체성(id·sourceUrl=link)은 item 에서 온다.
// reused·refreshNeeded 는 공유 등록(#853)의 attach 메타 — 등록 경로만 채우고 그 외(조회·수기 수정 등)는 기본값 false.
// sourcePlatform 은 응답 경계(WishlistService)가 링크에서 판정해 채운다. 영속화 계층은 비워 둔다.
data class WishWithItem(
    val wish: Wish,
    val item: Item,
    val snapshot: ItemSnapshot,
    val reused: Boolean = false,
    val refreshNeeded: Boolean = false,
    val sourcePlatform: String? = null,
)
