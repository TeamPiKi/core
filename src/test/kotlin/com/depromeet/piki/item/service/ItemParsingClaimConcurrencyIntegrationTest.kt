package com.depromeet.piki.item.service

import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.repository.ItemJpaRepository
import com.depromeet.piki.item.repository.ItemSnapshotJpaRepository
import com.depromeet.piki.support.IntegrationTestSupport
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.assertTrue

// outbox claim(FOR UPDATE)과 "PENDING snapshot 을 insert 중인 트랜잭션"의 공존을 검증한다.
//
// 배경: claim 의 FOR UPDATE 범위 스캔(status index)은 SKIP LOCKED 가 없으면 다른 트랜잭션의 미커밋 PENDING
// insert 에 **대기**하고, 그 트랜잭션의 다음 insert 는 claim 이 쥔 next-key/gap 락에 다시 대기해 교착 사이클이
// 성립한다(InnoDB 가 요청 쪽을 victim 으로 고르면 사용자 요청이 간헐 500 — TournamentItemImageAdd 동시성
// 테스트의 간헐 실패로 실측됨). SKIP LOCKED 면 claim 이 잠긴 entry 를 건너뛰어 대기 자체가 없고,
// 대기가 없으면 사이클도 성립할 수 없다.
//
// 이 테스트는 그 "대기 없음"을 결정적으로 고정한다: 미커밋 PENDING insert 가 락을 쥔 동안 claim 을 호출해
// 즉시 반환을 단언한다. SKIP LOCKED 가 빠지면 claim 이 락 해제(약 8초)까지 블록돼 2초 대기 단언이 확실히
// 깨진다 — 타이밍 우연에 기대지 않는 PASS/FAIL 분리다.
//
// CLAUDE.md '동시성 통합 테스트' 규약: 비-@Transactional(별도 트랜잭션 동시 진행이 본질), 자기 데이터 직접 정리.
class ItemParsingClaimConcurrencyIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var itemParsingService: ItemParsingService
    @Autowired private lateinit var itemJpaRepository: ItemJpaRepository
    @Autowired private lateinit var itemSnapshotJpaRepository: ItemSnapshotJpaRepository
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    @Timeout(30)
    fun `다른 트랜잭션이 PENDING insert 락을 쥔 동안에도 claim 은 대기 없이 반환된다`() {
        val insertedItemId = AtomicLong(0)
        val inserted = CountDownLatch(1)
        val release = CountDownLatch(1)

        // 별도 스레드 트랜잭션이 PENDING snapshot 을 INSERT(flush)만 하고 커밋하지 않은 채 락을 쥐고 버틴다 —
        // 요청 트랜잭션이 다건 insert 도중에 있는 상태의 재현.
        val holder = thread(name = "uncommitted-pending-insert") {
            TransactionTemplate(transactionManager).execute {
                val item = itemJpaRepository.save(Item(sourceImageKey = "items/raw/claim-skip-locked-test.jpg"))
                insertedItemId.set(item.getId())
                itemSnapshotJpaRepository.saveAndFlush(ItemSnapshot.pending(item.getId()))
                inserted.countDown()
                // 단언이 끝날 때까지 락 보유. 최대 8초 — claim 이 여기 대기하면 아래 2초 단언이 확실히 깨진다.
                release.await(8, TimeUnit.SECONDS)
            }
        }

        try {
            assertTrue(inserted.await(5, TimeUnit.SECONDS), "미커밋 PENDING insert 가 준비되어야 한다")

            // SKIP LOCKED 면 즉시 반환(미커밋 entry 는 건너뜀). 없으면 락 해제까지 블록 → 2초 초과로 실패.
            val claim = CompletableFuture.supplyAsync { itemParsingService.claimDuePending(100) }
            claim.get(2, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            holder.join(10_000)
            // holder 커밋으로 남은 행 정리 (동시성 테스트는 자기 데이터를 직접 지운다).
            jdbcTemplate.update("DELETE FROM item_snapshots WHERE item_id = ?", insertedItemId.get())
            jdbcTemplate.update("DELETE FROM items WHERE id = ?", insertedItemId.get())
        }
    }
}
