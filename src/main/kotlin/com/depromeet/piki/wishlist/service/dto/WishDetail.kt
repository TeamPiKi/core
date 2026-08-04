package com.depromeet.piki.wishlist.service.dto

import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.wishlist.domain.Wish

// 위시 상세 — wish 기록·상품 정체성(item)·표시 버전(snapshot)에 그 상품의 가격 이력(history)을 더한 묶음.
// 단건 조회가 이력까지 한 번에 내려주므로 클라이언트는 상세 화면에서 왕복 1회로 끝난다.
//
// history 는 요청자 기준으로 걸러진 가격 이력이다 — 기계(SERVER/SERVER_LLM) 버전 전부와 **본인이 입력한** 수기만 담고,
// 타인의 수기와 출처 미상 버전은 빠진다. 그 집합이 표시값 파생(#857)의 입력과 정확히 일치하므로, 표시 버전이 READY 인 한
// history 의 첫 항목이 곧 snapshot 이다. 진행 중(PENDING/PROCESSING)·실패(FAILED)일 때만 history 에서 빠진다.
data class WishDetail(
    val wish: Wish,
    val item: Item,
    val snapshot: ItemSnapshot,
    val history: List<ItemSnapshot>,
)
