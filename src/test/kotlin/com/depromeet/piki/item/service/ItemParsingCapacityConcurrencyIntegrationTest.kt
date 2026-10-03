package com.depromeet.piki.item.service

import com.depromeet.piki.common.config.AsyncConfig
import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemParseOutboxStatus
import com.depromeet.piki.item.repository.ItemRepository
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubLinkSnapshotExtractor
import com.depromeet.piki.support.deleteItems
import com.depromeet.piki.support.parseOutboxStatusOf
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

// 풀에 대기실이 없으므로(AsyncConfig) 풀이 차 있으면 집지 않고 아웃박스에서 기다리게 해야 한다.
class ItemParsingCapacityConcurrencyIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var itemParsingScheduler: ItemParsingScheduler

    @Autowired private lateinit var parsingEnqueuer: ParsingEnqueuer

    @Autowired private lateinit var itemRepository: ItemRepository

    @Autowired private lateinit var stubLinkSnapshotExtractor: StubLinkSnapshotExtractor

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    @Qualifier(AsyncConfig.ITEM_PARSING_EXECUTOR)
    private lateinit var itemParsingExecutor: ThreadPoolTaskExecutor

    @Test
    @Timeout(90)
    fun `풀이 가득 차면 집지 않고 슬롯이 나면 그때 집는다`() {
        stubLinkSnapshotExtractor.build = { ProductSnapshot(link = it, name = "상품", price = 1_000) }
        val release = CountDownLatch(1)
        val slots = itemParsingExecutor.maxPoolSize
        var itemId = 0L
        try {
            await().atMost(Duration.ofSeconds(30)).until { itemParsingExecutor.activeCount == 0 }
            repeat(slots) { itemParsingExecutor.execute { release.await(60, TimeUnit.SECONDS) } }
            await().atMost(Duration.ofSeconds(10)).until { itemParsingExecutor.activeCount >= slots }

            itemId = itemRepository.save(Item(ProductLink.parse("https://shop.example.com/products/capacity-${UUID.randomUUID()}"))).getId()
            TransactionTemplate(transactionManager).executeWithoutResult { parsingEnqueuer.enqueue(itemId, requestedBy = UUID.randomUUID()) }

            itemParsingScheduler.dispatch()
            assertEquals(ItemParseOutboxStatus.PENDING, jdbcTemplate.parseOutboxStatusOf(itemId), "가용 슬롯이 없으면 대기해야 한다")

            release.countDown()
            await().atMost(Duration.ofSeconds(30)).until { itemParsingExecutor.activeCount == 0 }
            itemParsingScheduler.dispatch()
            await().atMost(Duration.ofSeconds(10)).until { jdbcTemplate.parseOutboxStatusOf(itemId) == ItemParseOutboxStatus.SUCCEEDED }
        } finally {
            release.countDown()
            if (itemId != 0L) jdbcTemplate.deleteItems(listOf(itemId))
        }
    }
}
