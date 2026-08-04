package com.depromeet.piki.wishlist.service.dto

import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.wishlist.domain.Wish

// 위시 상세 — wish 기록·상품 정체성(item)·표시 버전(snapshot)에 그 상품의 가격 이력(history)을 더한 묶음.
// 단건 조회가 이력까지 한 번에 내려주므로 클라이언트는 상세 화면에서 왕복 1회로 끝난다.
//
// history 는 그 상품의 가격 기록이다 — 출처가 남은 READY 버전(서버 추출·수기 모두, 편집자 무관)을 담고 출처 미상만 빠진다.
// snapshot 과는 서로 다른 축이다: snapshot 은 표시값 파생(#857)을 거친 "지금 화면에 보일 버전"이라 맥락 스코프(내 수기 존중,
// 타인 수기 무시)를 타는 반면, history 는 그 필터를 타지 않는다. 그래서 history 의 첫 항목이 snapshot 과 다를 수 있고
// (타인의 수기가 가장 최신인 경우 등) 표시 버전이 진행 중·실패면 history 에 아예 없다 — 둘을 맞춰볼 대상이 아니다.
data class WishDetail(
    val wish: Wish,
    val item: Item,
    val snapshot: ItemSnapshot,
    val history: List<ItemSnapshot>,
)
