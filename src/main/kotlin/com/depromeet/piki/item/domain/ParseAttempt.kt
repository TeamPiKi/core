package com.depromeet.piki.item.domain

// 회수는 횟수를 올리므로 회수 전에 집은 워커의 결과는 기록 때 걸러짐
data class ParseAttempt(
    val itemParseOutboxId: Long,
    val recoveryCount: Int,
)
