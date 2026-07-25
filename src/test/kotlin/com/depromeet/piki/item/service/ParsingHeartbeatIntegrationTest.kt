package com.depromeet.piki.item.service

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.repository.ItemRepository
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubProductLinkExtractor
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull

// 소유권 fencing(#802) 검증 — attempt 토큰이 어긋난 좀비 워커의 결과가 전이·ext 호출로 새지 않음을 확인한다.
//
// 비-@Transactional: 시작 가드 검증이 실제 @Async 워커(별도 스레드·트랜잭션)를 태우므로 커밋된 데이터가 필요하다.
// 자기가 만든 행은 격리된 URL·itemId 로 구분해 메서드 끝에서 직접 정리한다(CLAUDE.md '동시성·시간 의존 통합 테스트').
class ParsingHeartbeatIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var itemParsingService: ItemParsingService

    @Autowired private lateinit var asyncItemParsingWorker: AsyncItemParsingWorker

    @Autowired private lateinit var stubProductLinkExtractor: StubProductLinkExtractor

    @Autowired private lateinit var itemRepository: ItemRepository

    @Autowired private lateinit var itemSnapshotRepository: ItemSnapshotRepository

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `claim attempt 와 어긋난 결과는 markReady 가 전이하지 않고 폐기한다`() {
        val item = itemRepository.save(Item(ProductLink.parse("https://shop.example.com/products/fence-${UUID.randomUUID()}")))
        val snapshot = itemSnapshotRepository.save(ItemSnapshot.pending(item.getId()).apply { markProcessing() }) // attempt 1
        val snapshotId = snapshot.getId()
        try {
            // 재클레임으로 attempt 2 가 된(소유권 이전) 상황을 DB 에 반영. updated_at=now 라 배경 recover 가 안 건드린다.
            jdbcTemplate.update("UPDATE item_snapshots SET attempt_count = 2, updated_at = ? WHERE id = ?", LocalDateTime.now(), snapshotId)

            // 옛 시도(attempt 1)의 결과로 markReady → fencing 으로 전이 없이 폐기(좀비 결과).
            itemParsingService.markReady(
                snapshotId,
                ProductSnapshot(link = item.link, name = "좀비결과", currentPrice = 1_000, currency = "KRW", imageUrl = "https://img.example.com/z.png"),
                expectedAttempt = 1,
            )

            val reloaded = itemSnapshotRepository.findById(snapshotId) ?: error("행 없음")
            assertEquals(ItemStatus.PROCESSING, reloaded.status, "좀비 결과는 READY 로 전이하면 안 된다")
            assertNull(reloaded.name, "좀비 결과의 추출값이 반영되면 안 된다")
            assertEquals(2, reloaded.attemptCount, "소유권을 쥔 새 시도(attempt 2)는 그대로여야 한다")
        } finally {
            deleteItem(item.getId())
        }
    }

    @Test
    fun `소유권 attempt 가 일치하면 markReady 가 정상 전이한다`() {
        // fencing 대조군 — 어긋날 때만 막고, 일치하면 그대로 전이함을 함께 고정한다. 워커를 태우지 않으므로 stub 세팅은 불필요하다.
        val item = itemRepository.save(Item(ProductLink.parse("https://shop.example.com/products/match-${UUID.randomUUID()}")))
        val snapshot = itemSnapshotRepository.save(ItemSnapshot.pending(item.getId()).apply { markProcessing() }) // attempt 1
        val snapshotId = snapshot.getId()
        try {
            itemParsingService.markReady(
                snapshotId,
                ProductSnapshot(link = item.link, name = "정상결과", currentPrice = 2_000, currency = "KRW", imageUrl = "https://img.example.com/ok.png"),
                expectedAttempt = 1,
            )

            val reloaded = itemSnapshotRepository.findById(snapshotId) ?: error("행 없음")
            assertEquals(ItemStatus.READY, reloaded.status)
            assertEquals("정상결과", reloaded.name)
        } finally {
            deleteItem(item.getId())
        }
    }

    @Test
    fun `시작 가드 — 소유권을 잃은 claim 은 워커가 ext 를 호출하지 않고 스킵한다`() {
        val extCalls = AtomicInteger(0)
        stubProductLinkExtractor.build = {
            extCalls.incrementAndGet()
            ProductSnapshot(link = it, name = "호출됨", currentPrice = 1_000, currency = "KRW", imageUrl = "https://img.example.com/c.png")
        }
        val item = itemRepository.save(Item(ProductLink.parse("https://shop.example.com/products/guard-${UUID.randomUUID()}")))
        val snapshot = itemSnapshotRepository.save(ItemSnapshot.pending(item.getId()).apply { markProcessing() }) // attempt 1
        val snapshotId = snapshot.getId()

        // 워커의 스킵은 부수효과가 없어 로그가 유일한 완료 신호다 — 그 로그로 완료를 관측해 "ext 미호출"을 결정적으로 단언한다.
        val workerLogger = LoggerFactory.getLogger(AsyncItemParsingWorker::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        workerLogger.addAppender(appender)
        try {
            // 재클레임으로 attempt 2 가 된 뒤(소유권 이전), 옛 시도(attempt 1)의 claim 이 뒤늦게 워커에 도착한 상황.
            jdbcTemplate.update("UPDATE item_snapshots SET attempt_count = 2, updated_at = ? WHERE id = ?", LocalDateTime.now(), snapshotId)

            // 옛 시도(attempt 1)로 워커 실행 — 시작 가드의 fenced touch 가 0행이라 ext 호출 없이 스킵해야 한다.
            asyncItemParsingWorker.parse(item.getId(), snapshotId, item.link ?: error("link 없음"), 1)

            // 스킵 완료(로그)를 기다린 뒤 ext 가 한 번도 안 불렸음을 단언한다. list 동시 접근 CME 는 ignoreExceptions 로 흡수.
            await().ignoreExceptions().atMost(Duration.ofSeconds(5)).until {
                appender.list.any { it.formattedMessage.contains("item.parse.skip") && it.formattedMessage.contains("snapshot=$snapshotId") }
            }
            assertEquals(0, extCalls.get(), "소유권을 잃은 좀비 워커는 ext 를 호출하면 안 된다")
            // 소유권을 쥔 attempt 2 는 여전히 PROCESSING (좀비가 아무 전이도 안 함).
            assertEquals(ItemStatus.PROCESSING, itemSnapshotRepository.findById(snapshotId)?.status)
        } finally {
            workerLogger.detachAppender(appender)
            deleteItem(item.getId())
        }
    }

    private fun deleteItem(itemId: Long) {
        jdbcTemplate.update("DELETE FROM item_snapshots WHERE item_id = ?", itemId)
        jdbcTemplate.update("DELETE FROM items WHERE id = ?", itemId)
    }
}
