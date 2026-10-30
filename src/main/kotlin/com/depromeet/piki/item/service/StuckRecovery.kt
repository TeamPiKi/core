package com.depromeet.piki.item.service

sealed interface StuckRecovery {
    val itemParseOutboxId: Long

    data class Requeued(
        override val itemParseOutboxId: Long,
    ) : StuckRecovery

    // 회수 상한을 넘겨 실패로 닫음
    data class Failed(
        override val itemParseOutboxId: Long,
    ) : StuckRecovery
}
