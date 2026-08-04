package com.depromeet.piki.wishlist.service.dto

import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.wishlist.domain.Wish

// 위시 상세 — wish 기록·상품 정체성(item)·표시 버전(snapshot)에 그 상품의 가격 이력(history)을 더한 묶음.
// 단건 조회가 이력까지 한 번에 내려주므로 클라이언트는 상세 화면에서 왕복 1회로 끝난다.
//
// snapshot 과 history 는 **서로 다른 축**이다. snapshot 은 표시값 파생(#857)을 거친 "지금 화면에 보일 버전"이고,
// history 는 그 상품의 기계 관측 시계열이다. 그래서 표시 버전이 수기(MANUAL)이거나 진행 중(PENDING/PROCESSING)·
// 실패(FAILED)면 history 에 그 버전이 없는 것이 정상이며, 둘을 조인해 맞춰볼 대상이 아니다.
data class WishDetail(
    val wish: Wish,
    val item: Item,
    val snapshot: ItemSnapshot,
    val history: List<ItemSnapshot>,
)
