package com.depromeet.piki.item.service

import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.repository.ItemJpaRepository
import com.depromeet.piki.item.repository.ItemSnapshotJpaRepository
import com.depromeet.piki.support.IntegrationTestSupport
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// #648 — TransitionRetry.isTransient 가 "실제" MySQL 데드락 예외를 일시로 알아보는지 실DB(Testcontainers)로 검증한다.
// 단위 테스트(TransitionRetryTest)는 매칭되는 예외를 손으로 만들어 던질 뿐이라, 진짜 데드락이 우리가 아는 타입으로
// 오는지는 증명하지 못한다. markReady 가 실제 겪는 경로(dirty-checking UPDATE 가 commit/flush 시점에 충돌)를 재현해
// 그 실제 예외를 잡고, transitionRetry.execute 가 그걸 일시로 분류해 재시도하는지를 단언한다.
//
// 동시성 테스트라 @Transactional 을 쓰지 않는다(별도 tx 동시 진행이 본질). 데이터는 finally 에서 직접 정리한다.
class TransitionRetryDeadlockConcurrencyIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var transitionRetry: TransitionRetry
    @Autowired private lateinit var itemJpaRepository: ItemJpaRepository
    @Autowired private lateinit var itemSnapshotJpaRepository: ItemSnapshotJpaRepository
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var entityManager: EntityManager
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `실제 InnoDB 데드락(commit 시점)으로 던져진 예외를 transitionRetry 가 일시로 보고 재시도한다`() {
        val items = itemJpaRepository.saveAll(listOf(Item(), Item()))
        val itemIds = items.map { it.getId() }
        val id1 = itemSnapshotJpaRepository.save(processingSnapshot(itemIds[0])).getId()
        val id2 = itemSnapshotJpaRepository.save(processingSnapshot(itemIds[1])).getId()

        try {
            // 두 tx 가 (id1→id2)·(id2→id1) 순으로 dirty-update 한다. explicit flush 로 각자 첫 행 락을 잡고, barrier 로 둘 다
            // 첫 락을 쥔 걸 보장한 뒤, TransactionTemplate commit 의 flush 에서 두 번째 UPDATE 가 교차로 막혀 InnoDB 데드락이 난다.
            // 이게 markReady 의 실제 경로(전이 write 가 commit 시점에 충돌)다.
            val tx = TransactionTemplate(transactionManager)
            val bothFirstLocked = CountDownLatch(2)
            val done = CountDownLatch(2)
            val captured = AtomicReference<Throwable?>(null)
            val committed = AtomicInteger(0)
            val executor = Executors.newFixedThreadPool(2)

            listOf(id1 to id2, id2 to id1).forEach { (first, second) ->
                executor.submit {
                    try {
                        tx.executeWithoutResult {
                            entityManager.find(ItemSnapshot::class.java, first).markFailed()
                            entityManager.flush() // 첫 UPDATE — 첫 행 X락 획득
                            bothFirstLocked.countDown()
                            bothFirstLocked.await() // 둘 다 첫 락을 쥘 때까지 대기
                            entityManager.find(ItemSnapshot::class.java, second).markFailed()
                            // 두 번째 UPDATE 는 commit 의 flush 에서 실행 → 교차 락 대기 → 데드락
                        }
                        committed.incrementAndGet()
                    } catch (e: Throwable) {
                        captured.compareAndSet(null, e) // victim 예외 1건 포착
                    } finally {
                        done.countDown()
                    }
                }
            }

            try {
                assertTrue(done.await(20, TimeUnit.SECONDS), "두 트랜잭션이 20초 안에 끝나야 한다")
            } finally {
                executor.shutdownNow()
            }

            val deadlockEx = captured.get()
            assertNotNull(deadlockEx, "InnoDB 가 victim 을 골라 예외를 던져야 한다 (하나는 commit, 하나는 deadlock)")
            assertEquals(1, committed.get(), "정확히 하나만 commit, 하나는 deadlock victim 이어야 한다")
            // 실제 예외 타입 사슬을 남긴다 — 미매칭이면(테스트 실패) 이 출력으로 SQLState 보강 판단을 한다.
            println("[#648 deadlock 실제 예외] ${causeChain(deadlockEx)}")

            // 핵심 단언: 그 "실제" 데드락 예외를 transitionRetry 가 일시로 분류해 재시도하는가.
            // attempt 1 에 실제 예외를 던지고 2 에 성공 → 재시도했으면 일시로 본 것(= isTransient 가 실제 데드락을 알아봄).
            val attempts = AtomicInteger(0)
            val result =
                transitionRetry.execute {
                    if (attempts.incrementAndGet() == 1) throw deadlockEx
                    "recovered"
                }
            assertEquals("recovered", result, "실제 데드락 예외가 일시로 분류돼 재시도로 살아나야 한다")
            assertEquals(2, attempts.get(), "1회 재시도 후 성공이어야 한다")
        } finally {
            val snapPlaceholders = listOf(id1, id2).joinToString(",") { "?" }
            jdbcTemplate.update("DELETE FROM item_snapshots WHERE id IN ($snapPlaceholders)", id1, id2)
            val itemPlaceholders = itemIds.joinToString(",") { "?" }
            jdbcTemplate.update("DELETE FROM items WHERE id IN ($itemPlaceholders)", *itemIds.toTypedArray())
        }
    }

    private fun processingSnapshot(itemId: Long): ItemSnapshot =
        ItemSnapshot(
            itemId = itemId,
            name = "deadlock-probe",
            price = 1_000,
            currency = "KRW",
            status = ItemStatus.PROCESSING,
            extractedAt = LocalDateTime.now(),
        )

    private fun causeChain(e: Throwable): String =
        generateSequence(e) { it.cause }
            .joinToString(" <- ") { "${it::class.java.name}(${it.message?.take(80)})" }
}
