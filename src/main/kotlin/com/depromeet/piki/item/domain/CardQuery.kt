package com.depromeet.piki.item.domain

import java.time.LocalDateTime
import java.util.UUID

data class CardQuery(
    val slot: ItemSlot,
    val itemId: Long?,
    val owner: UUID,

    // asOf 가 있으면 그 시각까지의 버전으로 계산한다(시작한 토너먼트)
    val asOf: LocalDateTime? = null,
)
