package com.depromeet.piki.item.event

import com.depromeet.piki.common.event.NotificationEvent

// 아이템 파싱 실패 — 도메인 사실.
data class ItemParsingFailed(
    val itemId: Long,
    // ItemParsingCompleted 와 같은 이유(#576) — 라우팅은 버전 단위가 정확하다.
    val snapshotId: Long,
) : NotificationEvent
