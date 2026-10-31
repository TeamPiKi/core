package com.depromeet.piki.common.outbox

import java.time.Duration

data class OutboxPolicy(
    val stuckAfter: Duration,
    val maxRecoveries: Int,
    val processingLimit: Duration,
) {
    init {
        require(stuckAfter.isPositive) { "회수 기준($stuckAfter)은 양수여야 한다" }
        require(maxRecoveries >= 0) { "회수 상한($maxRecoveries)은 0 이상이어야 한다" }
        // 처리 중에는 행을 잠그지 않아 처리 상한이 회수 기준보다 길면 살아 있는 워커의 요청을 회수함
        require(processingLimit < stuckAfter) {
            "처리 상한(${processingLimit.toMillis()}ms)은 회수 기준(${stuckAfter.toMillis()}ms)보다 짧아야 한다"
        }
    }
}
