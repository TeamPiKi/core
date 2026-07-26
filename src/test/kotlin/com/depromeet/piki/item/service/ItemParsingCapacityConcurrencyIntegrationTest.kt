package com.depromeet.piki.item.service

import com.depromeet.piki.common.config.AsyncConfig
import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.repository.ItemRepository
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubItemParsingWorker
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 디스패처의 "가용 슬롯만큼만 claim" 원칙 검증 (#802).
//
// 배경: 워커 풀에 대기실이 없으므로(AsyncConfig.itemParsingExecutor, queueCapacity=0) 집은 작업은 곧바로 실행에
// 들어간다. 풀이 가득 차면 집지 않고 PENDING 으로 남겨, 대기를 휘발성 인메모리 큐가 아니라 durable 한 DB 에서 한다.
// 옛 설정(큐 100)에서는 8 스레드가 다 차 있어도 100건을 집어 큐가 받았고, 그 행들은 실행 전인데도 PROCESSING 으로
// 위장됐다(크래시 시 recover 비용, 큐가 차면 거부돼 attempt 소진). 이 테스트가 그 회귀를 고정한다.
//
// recover 쪽은 비대칭이 본질이다: 재실행은 워커 슬롯을 쓰므로 슬롯만큼만 하고(reclaim 이 attempt 를 먼저 태우므로
// 제출도 못 할 재실행을 예약하면 재시도 기회만 잃는다), 종결(FAILED)은 슬롯이 필요 없어 무관하게 진행한다
// (슬롯으로 막으면 풀이 오래 포화일 때 종결이 영영 밀린다).
//
// CLAUDE.md '동시성 통합 테스트' 규약: 비-@Transactional(풀 점유가 별도 스레드에서 진행되는 것이 본질), 자기 데이터 직접 정리.
class ItemParsingCapacityConcurrencyIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var itemParsingScheduler: ItemParsingScheduler

    @Autowired private lateinit var itemParsingService: ItemParsingService

    @Autowired private lateinit var itemRepository: ItemRepository

    @Autowired private lateinit var itemSnapshotRepository: ItemSnapshotRepository

    @Autowired private lateinit var stubItemParsingWorker: StubItemParsingWorker

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    @Qualifier(AsyncConfig.ITEM_PARSING_EXECUTOR)
    private lateinit var itemParsingExecutor: ThreadPoolTaskExecutor

    @Test
    @Timeout(90)
    fun `풀이 가득 차면 PENDING 을 claim 하지 않고 슬롯이 나면 그때 집는다`() {
        // claim 여부만 검증하므로 실제 파싱은 끈다 (풀을 다시 점유해 슬롯 관측을 흐리지 않게).
        stubItemParsingWorker.enabled = false
        val release = CountDownLatch(1)
        val slots = itemParsingExecutor.maxPoolSize
        var itemId = 0L
        try {
            // 다른 테스트의 잔여 작업이 슬롯을 쥐고 있으면 점유 수가 어긋나므로 idle 을 먼저 기다린다.
            await().atMost(Duration.ofSeconds(30)).until { itemParsingExecutor.activeCount == 0 }
            repeat(slots) {
                itemParsingExecutor.execute {
                    release.await(60, TimeUnit.SECONDS)
                }
            }
            await().atMost(Duration.ofSeconds(10)).until { itemParsingExecutor.activeCount >= slots }

            // 풀이 가득 찬 뒤에 일감을 만든다 — 그전에 만들면 배경 디스패처가 먼저 집어 전제가 깨진다.
            val item = itemRepository.save(Item(ProductLink.parse("https://shop.example.com/products/capacity-${UUID.randomUUID()}")))
            itemId = item.getId()
            val snapshotId = itemSnapshotRepository.save(ItemSnapshot.pending(itemId)).getId()

            itemParsingScheduler.dispatch()
            assertEquals(ItemStatus.PENDING, statusOf(snapshotId), "가용 슬롯이 없으면 claim 하지 않고 PENDING 으로 남겨야 한다")

            // 슬롯이 나면 같은 행을 집는다 (배경 디스패처가 먼저 집을 수도 있어 상태로 확인한다).
            release.countDown()
            await().atMost(Duration.ofSeconds(30)).until { itemParsingExecutor.activeCount == 0 }
            itemParsingScheduler.dispatch()
            await().atMost(Duration.ofSeconds(10)).until { statusOf(snapshotId) == ItemStatus.PROCESSING }
        } finally {
            release.countDown()
            stubItemParsingWorker.enabled = true
            deleteItem(itemId)
        }
    }

    @Test
    fun `재실행 슬롯이 없어도 종결은 진행하고 재실행 대상의 attempt 는 태우지 않는다`() {
        val exhausted = staleProcessing(attempt = 2) // 상한 도달 → 종결 대상
        val retryable = staleProcessing(attempt = 1) // 재실행 대상
        try {
            // retrySlots = 0 — 워커 슬롯이 하나도 없는 상황을 직접 재현한다.
            val outcome = itemParsingService.retryOrFailStaleProcessing(LocalDateTime.now(), 100, 2, 0)

            assertTrue(outcome.toRetry.isEmpty(), "슬롯이 없으면 재실행 대상을 예약하지 않아야 한다")
            assertEquals(ItemStatus.FAILED, statusOf(exhausted.second), "종결은 슬롯과 무관하게 진행돼야 한다")
            assertEquals(ItemStatus.PROCESSING, statusOf(retryable.second), "재실행 대상은 손대지 않고 남겨야 한다")
            assertEquals(1, attemptOf(retryable.second), "reclaim 을 미뤘으므로 attempt 를 태우지 않아야 한다")
        } finally {
            deleteItem(exhausted.first)
            deleteItem(retryable.first)
        }
    }

    // stale 판정 대상이 될 PROCESSING 행을 만든다. updated_at 은 배경 recover(threshold = now-60s)에는 안 걸리고
    // 이 테스트가 넘기는 threshold(now)에는 걸리도록 몇 초 전으로 둔다 — 배경 스케줄러와의 경합을 제거한다.
    private fun staleProcessing(attempt: Int): Pair<Long, Long> {
        val item = itemRepository.save(Item(ProductLink.parse("https://shop.example.com/products/slot-${UUID.randomUUID()}")))
        val snapshotId = itemSnapshotRepository.save(ItemSnapshot.pending(item.getId()).apply { markProcessing() }).getId()
        jdbcTemplate.update(
            "UPDATE item_snapshots SET attempt_count = ?, updated_at = ? WHERE id = ?",
            attempt,
            LocalDateTime.now().minusSeconds(5),
            snapshotId,
        )
        return item.getId() to snapshotId
    }

    private fun statusOf(snapshotId: Long): ItemStatus =
        itemSnapshotRepository.findById(snapshotId)?.status ?: error("snapshot $snapshotId 이 없다")

    private fun attemptOf(snapshotId: Long): Int =
        itemSnapshotRepository.findById(snapshotId)?.attemptCount ?: error("snapshot $snapshotId 이 없다")

    private fun deleteItem(itemId: Long) {
        if (itemId == 0L) return
        jdbcTemplate.update("DELETE FROM item_snapshots WHERE item_id = ?", itemId)
        jdbcTemplate.update("DELETE FROM items WHERE id = ?", itemId)
    }
}
