package com.depromeet.piki.item.event

// 아이템 파싱 완료 — 도메인 사실(fact). 이 이벤트를 알림·통계·audit 등 어떤 소비자가 구독하든
// item 도메인은 그 존재를 모른다 (EDD 단방향 결합 — 소비자가 도메인을 import 한다).
data class ItemParsingCompleted(
    val itemId: Long,
    // 파싱 사실의 주체는 버전(snapshot)이다 — 한 item 에 여러 버전이 공존(갱신)하고 공유(#825)로 한 버전이
    // 여러 곳에 pin 될 수 있어, 소비자의 라우팅은 itemId 가 아니라 이 값으로 해야 카드·수신자가 정확히 짚인다(#576).
    val snapshotId: Long,
)
