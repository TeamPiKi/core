package com.depromeet.piki.item.service

import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.repository.ItemJpaRepository
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.deleteItems
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
import java.util.UUID

// 커밋 안 된 적재가 대기열 행을 잠근 동안에도 집기는 기다리지 않아야 한다. 기다리면 사용자 요청 트랜잭션과 교착한다.
class ItemParsingClaimConcurrencyIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var itemParsingService: ItemParsingService
    @Autowired private lateinit var itemJpaRepository: ItemJpaRepository
    @Autowired private lateinit var parsingEnqueuer: ParsingEnqueuer
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    @Timeout(30)
    fun `다른 트랜잭션이 적재 중인 대기열 행을 잠근 동안에도 집기는 대기 없이 반환된다`() {
        val insertedItemId = AtomicLong(0)
        val inserted = CountDownLatch(1)
        val release = CountDownLatch(1)

        val holder = thread(name = "uncommitted-pending-insert") {
            TransactionTemplate(transactionManager).execute {
                val item = itemJpaRepository.save(Item(sourceImageKey = "items/raw/claim-skip-locked-test.jpg"))
                insertedItemId.set(item.getId())
                parsingEnqueuer.enqueue(item.getId(), requestedBy = UUID.randomUUID())
                itemJpaRepository.flush()
                inserted.countDown()
                release.await(8, TimeUnit.SECONDS)
            }
        }

        try {
            assertTrue(inserted.await(5, TimeUnit.SECONDS), "미커밋 적재가 준비되어야 한다")

            val claim = CompletableFuture.supplyAsync { itemParsingService.claim(100) }
            claim.get(2, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            holder.join(10_000)
            jdbcTemplate.deleteItems(listOf(insertedItemId.get()))
        }
    }
}
