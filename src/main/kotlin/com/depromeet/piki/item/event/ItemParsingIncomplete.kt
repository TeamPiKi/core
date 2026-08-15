package com.depromeet.piki.item.event

// 아이템 파싱이 일부 필드만 채우고 끝났다 — 도메인 사실. 실패가 아니라 "사용자가 나머지를 채우면 완성되는 상태"라
// 완료·실패와 별개 사실로 둔다(#944). 소비자(알림)가 "나머지는 직접 채워주세요" 로 유도하는 근거다.
data class ItemParsingIncomplete(
    val itemId: Long,
    // ItemParsingCompleted 와 같은 이유(#576) — 라우팅은 버전 단위가 정확하다.
    val snapshotId: Long,
)
