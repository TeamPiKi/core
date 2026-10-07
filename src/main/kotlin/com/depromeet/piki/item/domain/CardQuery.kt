package com.depromeet.piki.item.domain

import java.time.LocalDateTime
import java.util.UUID

// asOf 가 있으면 그 시각까지의 버전으로 계산한다(시작한 토너먼트)
data class CardQuery(
    val requester: ParseRequester,
    val itemId: Long?,
    val owner: UUID,
    val asOf: LocalDateTime? = null,
)
