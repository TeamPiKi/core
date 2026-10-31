package com.depromeet.piki.common.outbox

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.data.domain.Limit
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

// RR 의 gap 잠금은 집기가 PENDING 범위 끝까지 훑어야 새 적재 자리에 닿음
private const val CLAIM_ALL = 1_000

// 새 아웃박스는 이 계약을 구현한 ConcurrencyIntegrationTest 하나로 공통 큐의 동시성 보장을 검증받는다
interface OutboxQueueContract<T : Outbox> {
    val queue: OutboxQueue<T>
    val jpaRepository: OutboxJpaRepository<T>
    val transactionManager: PlatformTransactionManager
    val jdbcTemplate: JdbcTemplate
    val table: String

    fun newPending(): T

    @Test
    @Timeout(30)
    fun `집기 트랜잭션이 열린 동안에도 새 PENDING 적재는 기다리지 않는다`() {
        val target = inTransaction { jpaRepository.save(newPending()) }
        val selected = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pausing =
            object : OutboxJpaRepository<T> by jpaRepository {
                override fun findPendingForUpdate(limit: Limit): List<T> =
                    jpaRepository.findPendingForUpdate(limit).also {
                        selected.countDown()
                        release.await(10, TimeUnit.SECONDS)
                    }
            }
        val pausingQueue =
            object : OutboxQueue<T>(pausing, transactionManager, queue.policy) {
                override fun onExhausted(outbox: T) = Unit
            }
        val claim = CompletableFuture.supplyAsync { pausingQueue.claim(CLAIM_ALL) }
        var insert: CompletableFuture<T>? = null

        try {
            assertTrue(selected.await(5, TimeUnit.SECONDS), "집기가 대기열 행을 잠가야 한다")
            insert = CompletableFuture.supplyAsync { inTransaction { jpaRepository.save(newPending()) } }
            try {
                insert.get(2, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                fail("집기 트랜잭션이 열린 동안 새 PENDING 적재가 2초 넘게 기다렸다", e)
            }
        } finally {
            release.countDown()
            runCatching { claim.get(10, TimeUnit.SECONDS) }
            delete(listOfNotNull(target, insert?.let { runCatching { it.get(10, TimeUnit.SECONDS) }.getOrNull() }))
        }
    }

    // 기다리면 사용자 요청 트랜잭션과 교착한다
    @Test
    @Timeout(30)
    fun `커밋 안 된 적재가 대기열 행을 잠근 동안에도 집기는 기다리지 않는다`() {
        val held = AtomicReference<T>()
        val inserted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder =
            thread(name = "uncommitted-pending-insert") {
                inTransaction {
                    held.set(jpaRepository.save(newPending()))
                    inserted.countDown()
                    release.await(8, TimeUnit.SECONDS)
                }
            }

        try {
            assertTrue(inserted.await(5, TimeUnit.SECONDS), "미커밋 적재가 준비되어야 한다")
            CompletableFuture.supplyAsync { queue.claim(CLAIM_ALL) }.get(2, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            holder.join(10_000)
            delete(listOfNotNull(held.get()))
        }
    }

    @Test
    fun `회수 뒤 다시 집히면 회수 전에 집은 시도는 행을 쥐지 못하고 되돌리지도 못한다`() {
        assumeTrue(queue.policy.maxRecoveries > 0, "회수 상한 0 이면 회수가 곧 실패라 다시 집히지 않음")
        val outbox = inTransaction { jpaRepository.save(newPending()) }

        try {
            val before = queue.claim(CLAIM_ALL).single { it.outboxId == outbox.getId() }
            markStuck(outbox)
            queue.recoverStuck(CLAIM_ALL)
            val after = queue.claim(CLAIM_ALL).single { it.outboxId == outbox.getId() }
            queue.requeue(before)

            assertFalse(isHeld(before))
            assertTrue(isHeld(after))
        } finally {
            delete(listOf(outbox))
        }
    }

    @Test
    fun `바깥 트랜잭션 안에서는 집기와 되돌리기와 회수를 거부한다`() {
        assertFailsWith<IllegalStateException> { inTransaction { queue.claim(1) } }
        assertFailsWith<IllegalStateException> { inTransaction { queue.requeue(OutboxAttempt(outboxId = 0L, recoveryCount = 0)) } }
        assertFailsWith<IllegalStateException> { inTransaction { queue.recoverStuck(1) } }
    }

    // 없어도 RC 라 위 테스트는 통과하지만 매초 도는 집기가 테이블 전체를 훑음
    @Test
    fun `구현 테이블에 status 와 created_at 으로 시작하는 인덱스가 있다`() {
        val indexes =
            jdbcTemplate.queryForList(
                "SELECT index_name FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = ? " +
                    "GROUP BY index_name " +
                    "HAVING GROUP_CONCAT(CASE WHEN seq_in_index <= 2 THEN column_name END ORDER BY seq_in_index) = 'status,created_at'",
                String::class.java,
                table,
            )

        assertTrue(indexes.isNotEmpty(), "$table 에 (status, created_at) 으로 시작하는 인덱스가 없다")
    }

    private fun <R : Any> inTransaction(block: () -> R): R = TransactionTemplate(transactionManager).execute { block() }

    private fun isHeld(attempt: OutboxAttempt): Boolean = inTransaction { jpaRepository.findHeldForUpdate(attempt)?.let { true } ?: false }

    // 테스트 컨텍스트는 스케줄링이 꺼져 있어 워커가 집은 뒤 멈춘 상태를 직접 만든다
    private fun markStuck(outbox: T) {
        val claimedAt = LocalDateTime.now().minus(queue.policy.stuckAfter).minusSeconds(1)
        jdbcTemplate.update("UPDATE $table SET updated_at = ? WHERE id = ?", Timestamp.valueOf(claimedAt), outbox.getId())
    }

    private fun delete(outboxes: Collection<T>) {
        outboxes.forEach { jdbcTemplate.update("DELETE FROM $table WHERE id = ?", it.getId()) }
    }
}
