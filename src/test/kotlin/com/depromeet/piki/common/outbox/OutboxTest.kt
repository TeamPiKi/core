package com.depromeet.piki.common.outbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutboxTest {
    @Test
    fun `새 아웃박스는 PENDING 이다`() {
        assertEquals(OutboxStatus.PENDING, pending().status)
    }

    @Test
    fun `PENDING 을 집으면 PROCESSING 이 된다`() {
        val outbox = pending()

        outbox.claim()

        assertEquals(OutboxStatus.PROCESSING, outbox.status)
    }

    @Test
    fun `PENDING 이 아닌 아웃박스는 집을 수 없다`() {
        assertFailsWith<IllegalStateException> { processing().claim() }
    }

    @Test
    fun `행을 쥔 시도는 성공 실패로 닫거나 PENDING 으로 되돌린다`() {
        listOf<Pair<(Outbox, OutboxAttempt) -> Unit, OutboxStatus>>(
            Outbox::succeed to OutboxStatus.SUCCEEDED,
            Outbox::fail to OutboxStatus.FAILED,
            Outbox::requeue to OutboxStatus.PENDING,
        ).forEach { (settle, expected) ->
            val outbox = processing()
            settle(outbox, FIRST_ATTEMPT)
            assertEquals(expected, outbox.status)
        }
    }

    @Test
    fun `PROCESSING 이 아니면 어떤 시도도 행을 쥐지 않아 닫거나 되돌릴 수 없다`() {
        listOf<(Outbox, OutboxAttempt) -> Unit>(Outbox::succeed, Outbox::fail, Outbox::requeue).forEach { settle ->
            val outbox = pending()
            assertFalse(outbox.isHeldBy(FIRST_ATTEMPT))
            assertFailsWith<IllegalStateException> { settle(outbox, FIRST_ATTEMPT) }
        }
    }

    @Test
    fun `회수 뒤 다시 집히면 회수 전에 집은 시도는 행을 놓쳐 닫을 수 없다`() {
        val outbox = processing()

        outbox.recover(maxRecoveries = 1)
        outbox.claim()

        assertFalse(outbox.isHeldBy(FIRST_ATTEMPT))
        assertFailsWith<IllegalStateException> { outbox.succeed(FIRST_ATTEMPT) }
        assertEquals(OutboxStatus.PROCESSING, outbox.status)
        assertTrue(outbox.isHeldBy(OutboxAttempt(OUTBOX_ID, recoveryCount = 1)))
    }

    @Test
    fun `닫힌 아웃박스는 다시 닫을 수 없다`() {
        val outbox = processing().apply { succeed(FIRST_ATTEMPT) }

        assertFailsWith<IllegalStateException> { outbox.fail(FIRST_ATTEMPT) }
    }

    @Test
    fun `멈춘 PROCESSING 을 회수하면 PENDING 으로 되돌아가고 회수 횟수가 는다`() {
        val outbox = processing()

        assertEquals(RecoveryOutcome.REQUEUED, outbox.recover(maxRecoveries = 1))
        assertEquals(OutboxStatus.PENDING, outbox.status)
        assertEquals(1, outbox.recoveryCount)
    }

    @Test
    fun `회수 횟수가 상한에 닿은 아웃박스가 다시 멈추면 되돌리지 않고 실패로 닫는다`() {
        val outbox = processing().apply { recover(maxRecoveries = 1) }
        outbox.claim()

        assertEquals(RecoveryOutcome.EXHAUSTED, outbox.recover(maxRecoveries = 1))
        assertEquals(OutboxStatus.FAILED, outbox.status)
        assertEquals(1, outbox.recoveryCount)
    }

    @Test
    fun `회수 상한이 0 이면 첫 회수에서 실패로 닫는다`() {
        val outbox = processing()

        assertEquals(RecoveryOutcome.EXHAUSTED, outbox.recover(maxRecoveries = 0))
        assertEquals(OutboxStatus.FAILED, outbox.status)
    }

    @Test
    fun `PROCESSING 이 아닌 아웃박스는 회수할 수 없다`() {
        assertFailsWith<IllegalStateException> { pending().recover(maxRecoveries = 1) }
    }

    private fun pending(): Outbox = TestOutbox()

    private fun processing(): Outbox = pending().apply { claim() }

    private class TestOutbox : Outbox()

    companion object {
        private const val OUTBOX_ID = 1L
        private val FIRST_ATTEMPT = OutboxAttempt(OUTBOX_ID, recoveryCount = 0)
    }
}
