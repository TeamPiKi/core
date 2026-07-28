package com.depromeet.piki.item.service

import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.repository.ItemRepository
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.support.IntegrationTestSupport
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ParsingHeartbeat 의 시계 조작 검증 (#802). 박동(renew)과 stale 판정은 시간 축이 본질이라 별도 타이밍 분류로 둔다.
//
// CLAUDE.md '동시성·시간 의존 통합 테스트' 규약: 비-@Transactional(박동은 별도 짧은 트랜잭션이 본질), 자기 데이터 직접 정리.
// 시계는 sleep 이 아니라 updated_at·registeredAt 데이터 조작으로 돌린다. 배경 recover(@Scheduled, threshold=now-60s)가
// 테스트 행을 가로채지 않도록, 검증 대상 행의 updated_at 은 항상 now-60s 보다 최신으로 둔다(stale 판정은 threshold 를 직접 넘겨 재현).
class ParsingHeartbeatTimingIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var parsingHeartbeat: ParsingHeartbeat

    @Autowired private lateinit var parsingOwnership: ParsingOwnership

    @Autowired private lateinit var itemRepository: ItemRepository

    @Autowired private lateinit var itemSnapshotRepository: ItemSnapshotRepository

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `박동(renew)은 소유권을 쥔 PROCESSING 의 updated_at 을 밀어 stale 판정에서 빼낸다`() {
        val item = itemRepository.save(Item(ProductLink.parse("https://shop.example.com/products/touch-${UUID.randomUUID()}")))
        val snapshot = itemSnapshotRepository.save(ItemSnapshot.pending(item.getId()).apply { markProcessing() }) // attempt 0 (집기는 예산 미소모)
        val snapshotId = snapshot.getId()
        try {
            // 워커가 실행에 진입해 소유권을 획득한 상태를 재현한다 (attempt 0 -> 1).
            assertEquals(1, parsingOwnership.acquire(snapshotId, 0), "실행 진입 시 소유권을 획득해야 한다")
            // threshold 를 지금으로 잡으면 그 직전에 갱신된 행(updated_at < now)은 stale 로 잡힌다.
            val threshold = LocalDateTime.now()
            assertTrue(snapshotId in staleIds(threshold), "renew 전에는 threshold 이전이라 stale 로 잡혀야 한다")

            // 박동(renew) — 소유권(attempt 1) 유지라 1행 매치, updated_at 이 threshold 이후로 밀린다.
            assertEquals(1, parsingOwnership.renew(snapshotId, 1), "소유권을 쥔 PROCESSING 은 1행 매치여야 한다")

            assertFalse(snapshotId in staleIds(threshold), "renew 후에는 updated_at 이 threshold 를 넘어 더는 stale 이 아니다")
        } finally {
            deleteItem(item.getId())
        }
    }

    @Test
    fun `beat 는 소유권을 쥔 산 항목을 갱신해 stale 에서 빼고 레지스트리에 유지한다`() {
        // renew 직접 호출이 아니라 @Scheduled 진입점 beat() 를 경유해 정상 갱신 분기(1행 매치 → 유지)를 회귀로 고정한다.
        val item = itemRepository.save(Item(ProductLink.parse("https://shop.example.com/products/beat-${UUID.randomUUID()}")))
        val snapshot = itemSnapshotRepository.save(ItemSnapshot.pending(item.getId()).apply { markProcessing() }) // attempt 0
        val snapshotId = snapshot.getId()
        try {
            // 워커가 실행에 진입해 소유권을 획득한 상태를 재현한다 (attempt 0 -> 1).
            assertEquals(1, parsingOwnership.acquire(snapshotId, 0), "실행 진입 시 소유권을 획득해야 한다")
            // 등록 전(auto-beat 대상 아님)에 threshold 를 잡으면 그 직전 갱신된 행은 그 시점 이전이라 stale 로 잡힌다 — 결정론.
            val threshold = LocalDateTime.now()
            assertTrue(snapshotId in staleIds(threshold), "beat 전(생성 직후)에는 threshold 이전이라 stale 이어야 한다")

            parsingHeartbeat.register(snapshotId, 1)
            parsingHeartbeat.beat()

            assertFalse(snapshotId in staleIds(threshold), "beat 의 정상 분기가 touch 해 updated_at 을 threshold 뒤로 밀어야 한다")
            assertTrue(parsingHeartbeat.isTracking(snapshotId), "소유권을 쥔 산 항목은 beat 후에도 레지스트리에 남아야 한다")
        } finally {
            parsingHeartbeat.deregister(snapshotId, 1)
            deleteItem(item.getId())
        }
    }

    @Test
    fun `소유권이 넘어간 행에 대한 옛 시도의 박동은 0행이고 beat 가 그 좀비를 레지스트리에서 제거한다`() {
        val item = itemRepository.save(Item(ProductLink.parse("https://shop.example.com/products/zombie-${UUID.randomUUID()}")))
        val snapshot = itemSnapshotRepository.save(ItemSnapshot.pending(item.getId()).apply { markProcessing() }) // attempt 0
        val snapshotId = snapshot.getId()
        try {
            // 소유권이 다른 시도로 넘어간 상황 재현 — 행은 attempt 2 이고, updated_at 은 now(신선)라 배경 recover 가 가로채지 않는다.
            jdbcTemplate.update("UPDATE item_snapshots SET attempt_count = 2, updated_at = ? WHERE id = ?", LocalDateTime.now(), snapshotId)

            // 옛 시도(attempt 1)의 박동은 행(attempt 2)과 소유권이 안 맞아 0행이다(fencing).
            assertEquals(0, parsingOwnership.renew(snapshotId, 1), "소유권이 넘어간 행에 옛 attempt 로 박동하면 0행이어야 한다")

            // 그 좀비가 레지스트리에 남아 박동하면 beat 가 0행을 보고 제거한다.
            parsingHeartbeat.register(snapshotId, 1)
            parsingHeartbeat.beat()
            assertFalse(parsingHeartbeat.isTracking(snapshotId), "beat 가 소유권 잃은 좀비를 레지스트리에서 제거해야 한다")
        } finally {
            parsingHeartbeat.deregister(snapshotId, 1)
            deleteItem(item.getId())
        }
    }

    // 무한 행잉(박동은 성실한데 실행이 안 끝나는 경우)의 종결은 여기서 검증하지 않는다 — 박동에 별도 절대 캡을 두던 층을
    // 걷어내고 마감(created_at 기준)이 단독으로 책임지게 했기 때문이다. 그 회귀는
    // ItemParsingCapacityConcurrencyIntegrationTest 의 `마감을 넘긴 행은 attempt 가 남아 있어도 박동이 뛰어도 종결된다` 가 고정한다.

    // stale 스캔(FOR UPDATE SKIP LOCKED)은 트랜잭션이 필요하므로 짧은 트랜잭션으로 감싸 읽고 즉시 커밋(락 해제)한다.
    private fun staleIds(threshold: LocalDateTime): List<Long> =
        TransactionTemplate(transactionManager).execute {
            itemSnapshotRepository.findStaleProcessing(threshold, 200).map { it.getId() }
        } ?: emptyList()

    private fun deleteItem(itemId: Long) {
        jdbcTemplate.update("DELETE FROM item_snapshots WHERE item_id = ?", itemId)
        jdbcTemplate.update("DELETE FROM items WHERE id = ?", itemId)
    }
}
