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

// ParsingHeartbeat 의 시계 조작 검증 (#802). 박동·fenced touch·절대 캡은 시간 축이 본질이라 별도 타이밍 분류로 둔다.
//
// CLAUDE.md '동시성·시간 의존 통합 테스트' 규약: 비-@Transactional(박동은 별도 짧은 트랜잭션이 본질), 자기 데이터 직접 정리.
// 시계는 sleep 이 아니라 updated_at·registeredAt 데이터 조작으로 돌린다. 배경 recover(@Scheduled, threshold=now-60s)가
// 테스트 행을 가로채지 않도록, 검증 대상 행의 updated_at 은 항상 now-60s 보다 최신으로 둔다(stale 판정은 threshold 를 직접 넘겨 재현).
class ParsingHeartbeatTimingIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var parsingHeartbeat: ParsingHeartbeat

    @Autowired private lateinit var heartbeatTouch: HeartbeatTouch

    @Autowired private lateinit var itemRepository: ItemRepository

    @Autowired private lateinit var itemSnapshotRepository: ItemSnapshotRepository

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `fenced touch 는 소유권을 쥔 PROCESSING 의 updated_at 을 밀어 stale 판정에서 빼낸다`() {
        val item = itemRepository.save(Item(ProductLink.parse("https://shop.example.com/products/touch-${UUID.randomUUID()}")))
        val snapshot = itemSnapshotRepository.save(ItemSnapshot.pending(item.getId()).apply { markProcessing() }) // attempt 1
        val snapshotId = snapshot.getId()
        try {
            // threshold 를 지금으로 잡으면 방금 생성한 행(updated_at ≈ 생성시각 < now)은 stale 로 잡힌다.
            val threshold = LocalDateTime.now()
            assertTrue(snapshotId in staleIds(threshold), "touch 전에는 threshold 이전이라 stale 로 잡혀야 한다")

            // fenced touch — 소유권(attempt 1) 유지라 1행 매치, updated_at 이 threshold 이후로 밀린다.
            assertEquals(1, heartbeatTouch.touch(snapshotId, 1), "소유권을 쥔 PROCESSING 은 1행 매치여야 한다")

            assertFalse(snapshotId in staleIds(threshold), "touch 후에는 updated_at 이 threshold 를 넘어 더는 stale 이 아니다")
        } finally {
            deleteItem(item.getId())
        }
    }

    @Test
    fun `beat 는 소유권을 쥔 산 항목을 touch 해 stale 에서 빼고 레지스트리에 유지한다`() {
        // heartbeatTouch.touch 직접 호출이 아니라 @Scheduled 진입점 beat() 를 경유해 정상 갱신 분기(1행 매치 → 유지)를 회귀로 고정한다.
        val item = itemRepository.save(Item(ProductLink.parse("https://shop.example.com/products/beat-${UUID.randomUUID()}")))
        val snapshot = itemSnapshotRepository.save(ItemSnapshot.pending(item.getId()).apply { markProcessing() }) // attempt 1
        val snapshotId = snapshot.getId()
        try {
            // 등록 전(auto-beat 대상 아님)에 threshold 를 잡으면 방금 만든 행은 그 시점 이전이라 stale 로 잡힌다 — 결정론.
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
    fun `재클레임된 행에 대한 옛 시도의 touch 는 0행이고 beat 가 그 좀비를 레지스트리에서 제거한다`() {
        val item = itemRepository.save(Item(ProductLink.parse("https://shop.example.com/products/zombie-${UUID.randomUUID()}")))
        val snapshot = itemSnapshotRepository.save(ItemSnapshot.pending(item.getId()).apply { markProcessing() }) // attempt 1
        val snapshotId = snapshot.getId()
        try {
            // 재클레임 재현 — 행은 attempt 2 로 올라가 있고, updated_at 은 now(신선)라 배경 recover 가 가로채지 않는다.
            jdbcTemplate.update("UPDATE item_snapshots SET attempt_count = 2, updated_at = ? WHERE id = ?", LocalDateTime.now(), snapshotId)

            // 옛 시도(attempt 1)의 touch 는 행(attempt 2)과 소유권이 안 맞아 0행이다(fencing).
            assertEquals(0, heartbeatTouch.touch(snapshotId, 1), "재클레임된 행에 옛 attempt 로 touch 하면 0행이어야 한다")

            // 그 좀비가 레지스트리에 남아 박동하면 beat 가 0행을 보고 제거한다.
            parsingHeartbeat.register(snapshotId, 1)
            parsingHeartbeat.beat()
            assertFalse(parsingHeartbeat.isTracking(snapshotId), "beat 가 소유권 잃은 좀비를 레지스트리에서 제거해야 한다")
        } finally {
            parsingHeartbeat.deregister(snapshotId, 1)
            deleteItem(item.getId())
        }
    }

    @Test
    fun `절대 캡을 넘긴 항목은 beat 가 갱신 없이 레지스트리에서 제거해 recover 회수에 맡긴다`() {
        val item = itemRepository.save(Item(ProductLink.parse("https://shop.example.com/products/cap-${UUID.randomUUID()}")))
        val snapshot = itemSnapshotRepository.save(ItemSnapshot.pending(item.getId()).apply { markProcessing() }) // attempt 1
        val snapshotId = snapshot.getId()
        try {
            // 행의 updated_at 을 now-40s 로 — 배경 recover(now-60s) 대상은 아니면서(간섭 차단),
            // "beat 가 touch 했다면 now 로 밀렸을" 값과 구분 가능한 과거값.
            val staleMark = LocalDateTime.now().minusSeconds(40).truncatedTo(ChronoUnit.MICROS)
            jdbcTemplate.update("UPDATE item_snapshots SET updated_at = ? WHERE id = ?", staleMark, snapshotId)

            // 등록 시각을 절대 캡(5분) 이전으로 seed — 무한 행잉 워커 재현.
            parsingHeartbeat.trackFrom(snapshotId, 1, LocalDateTime.now().minusMinutes(6))
            parsingHeartbeat.beat()

            // 레지스트리에서 제거됐고,
            assertFalse(parsingHeartbeat.isTracking(snapshotId), "절대 캡 초과 항목은 레지스트리에서 제거돼야 한다")
            // updated_at 은 밀리지 않아 여전히 과거다(touch 였다면 now 였을 것) → recover 가 stale 로 회수한다.
            val after =
                jdbcTemplate.queryForObject("SELECT updated_at FROM item_snapshots WHERE id = ?", LocalDateTime::class.java, snapshotId)
                    ?: error("행 없음")
            assertTrue(after.isBefore(LocalDateTime.now().minusSeconds(20)), "절대 캡 초과 항목은 touch 되지 않아 updated_at 이 과거로 남아야 한다")
        } finally {
            parsingHeartbeat.deregister(snapshotId, 1)
            deleteItem(item.getId())
        }
    }

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
