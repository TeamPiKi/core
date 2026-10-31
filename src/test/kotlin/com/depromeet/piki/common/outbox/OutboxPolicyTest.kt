package com.depromeet.piki.common.outbox

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertFailsWith

class OutboxPolicyTest {
    @Test
    fun `처리 상한이 회수 기준보다 짧지 않으면 정책을 만들지 않는다`() {
        assertFailsWith<IllegalArgumentException> {
            OutboxPolicy(stuckAfter = Duration.ofMinutes(2), maxRecoveries = 1, processingLimit = Duration.ofMinutes(2))
        }
    }

    @Test
    fun `회수 기준이 양수가 아니면 정책을 만들지 않는다`() {
        assertFailsWith<IllegalArgumentException> {
            OutboxPolicy(stuckAfter = Duration.ZERO, maxRecoveries = 1, processingLimit = Duration.ofSeconds(-1))
        }
    }

    @Test
    fun `회수 상한이 음수면 정책을 만들지 않는다`() {
        assertFailsWith<IllegalArgumentException> {
            OutboxPolicy(stuckAfter = Duration.ofMinutes(2), maxRecoveries = -1, processingLimit = Duration.ofMinutes(1))
        }
    }
}
