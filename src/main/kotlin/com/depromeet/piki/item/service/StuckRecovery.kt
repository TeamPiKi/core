package com.depromeet.piki.item.service

import com.depromeet.piki.item.domain.RecoveryOutcome

data class StuckRecovery(
    val itemParseOutboxId: Long,
    val outcome: RecoveryOutcome,
)
