package com.depromeet.piki.common.outbox

data class OutboxRecovery(
    val outboxId: Long,
    val outcome: RecoveryOutcome,
)
