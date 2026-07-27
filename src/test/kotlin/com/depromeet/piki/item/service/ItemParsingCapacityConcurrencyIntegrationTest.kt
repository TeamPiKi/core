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
// recover 쪽은 비대칭이 본질이다: 되살림은 워커 슬롯을 쓰므로 슬롯만큼만 지목하고, 종결(FAILED)은 슬롯이 필요 없어
// 무관하게 진행한다(슬롯으로 막으면 풀이 오래 포화일 때 종결이 영영 밀린다).
// 마감(created_at) 종결도 여기서 함께 고정한다 — attempt 예산·박동과 무관한 벽시계라 PENDING 도 대상이다.
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
    fun `되살림 슬롯이 없어도 종결은 진행하고 되살림 대상의 attempt 는 태우지 않는다`() {
        val exhausted = staleProcessing(attempt = 2) // 실행 상한 도달 → 종결 대상
        val revivable = staleProcessing(attempt = 1) // 되살림 대상
        try {
            // reviveSlots = 0 — 워커 슬롯이 하나도 없는 상황을 직접 재현한다.
            val outcome = itemParsingService.reviveOrFailStale(LocalDateTime.now(), 100, 2, 0)

            assertTrue(outcome.toRevive.isEmpty(), "슬롯이 없으면 되살림 대상을 지목하지 않아야 한다")
            assertEquals(ItemStatus.FAILED, statusOf(exhausted.second), "종결은 슬롯과 무관하게 진행돼야 한다")
            assertEquals(ItemStatus.PROCESSING, statusOf(revivable.second), "되살림 대상은 손대지 않고 남겨야 한다")
            assertEquals(1, attemptOf(revivable.second), "지목을 미뤘으므로 실행 예산을 태우지 않아야 한다")
        } finally {
            deleteItem(exhausted.first)
            deleteItem(revivable.first)
        }
    }

    @Test
    fun `마감을 넘긴 행은 attempt 가 남아 있어도 박동이 뛰어도 종결된다`() {
        // 마감은 예산(attempt)이 아니라 벽시계(created_at)를 본다. 그래서 (a) 아직 집히지도 않은 PENDING 과
        // (b) 실행 예산이 남아 있고 박동으로 updated_at 이 신선한 PROCESSING 이 함께 종결된다 — 종결 보증의 최후 시계다.
        val pending = overdue(ItemStatus.PENDING)
        val beating = overdue(ItemStatus.PROCESSING)
        try {
            // threshold 를 지금으로 잡으면 위에서 created_at 을 과거로 민 두 행이 마감 대상이 된다.
            val expired = itemParsingService.failOverdue(LocalDateTime.now(), 100)

            assertTrue(expired >= 2, "마감 초과 행은 종결돼야 한다")
            assertEquals(ItemStatus.FAILED, statusOf(pending.second), "집히지 못한 PENDING 도 마감 대상이다")
            assertEquals(ItemStatus.FAILED, statusOf(beating.second), "박동이 신선해도 마감은 종결한다")
            assertEquals(0, attemptOf(beating.second), "마감은 예산을 소모하지 않고 종결한다")
        } finally {
            deleteItem(pending.first)
            deleteItem(beating.first)
        }
    }

    // created_at 을 과거로 민 마감 대상 행. updated_at 은 now 로 둬 "박동이 신선한데도 마감에 걸린다"를 재현한다.
    private fun overdue(status: ItemStatus): Pair<Long, Long> {
        val item = itemRepository.save(Item(ProductLink.parse("https://shop.example.com/products/overdue-${UUID.randomUUID()}")))
        val snapshot = ItemSnapshot.pending(item.getId()).apply { if (status == ItemStatus.PROCESSING) markProcessing() }
        val snapshotId = itemSnapshotRepository.save(snapshot).getId()
        jdbcTemplate.update(
            "UPDATE item_snapshots SET created_at = ?, updated_at = ? WHERE id = ?",
            LocalDateTime.now().minusMinutes(10),
            LocalDateTime.now(),
            snapshotId,
        )
        return item.getId() to snapshotId
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
