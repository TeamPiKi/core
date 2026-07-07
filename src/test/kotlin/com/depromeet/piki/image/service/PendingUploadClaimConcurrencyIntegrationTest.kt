package com.depromeet.piki.image.service

import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubImageSnapshotExtractor
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.wishlist.service.WishlistService
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 이미지 등록 v2 claim 동시성 — 이 PR 의 핵심 불변식(confirm·폴링이 같은 pending 을 동시에 다퉈도 FOR UPDATE 로 한쪽만 등록)을
// 실제 경합으로 검증한다. 순차 호출(멱등 no-op)로는 claim 이 락을 실제로 쥐는지 확인되지 않아, lock 범위 회귀(트랜잭션 분리·
// @Lock 누락 등)를 못 잡는다. 여기선 두 스레드를 CountDownLatch 로 동시 출발시켜 진짜 경합을 만든다.
//
// 동시성 검증 규약(testing-convention): @Transactional 을 쓰지 않는다(별도 트랜잭션 동시 진행이 시뮬레이션의 본질),
// 격리된 UUID + 명시 cleanup 으로 공유 컨텍스트를 정리, 동시 출발은 CountDownLatch 로 강제(Thread.sleep 금지).
class PendingUploadClaimConcurrencyIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var wishlistService: WishlistService

    @Autowired
    private lateinit var pollingScheduler: PendingUploadPollingScheduler

    @Autowired
    private lateinit var stubImageSnapshotExtractor: StubImageSnapshotExtractor

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `같은 key 로 동시에 confirm 두 건이 들어와도 위시가 중복 생성되지 않는다`() {
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            seedExtractor()
            val keys = wishlistService.presignImageUploads(listOf("image/png", "image/jpeg"), userId).map { it.imageKey }

            val startLatch = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            val futures =
                (1..2).map {
                    executor.submit(
                        Callable {
                            startLatch.await()
                            // 예외(락 경합 실패 등)면 -1 로 표시해 스레드가 정상 종료했는지 단언할 수 있게 한다.
                            runCatching { wishlistService.confirmImageRegistration(keys, userId).size }.getOrElse { -1 }
                        },
                    )
                }
            startLatch.countDown() // 두 스레드 동시 출발
            val sizes = futures.map { it.get(10, TimeUnit.SECONDS) }
            executor.shutdown()

            // 두 confirm 모두 예외 없이 끝나고, 등록된 합은 정확히 2 여야 한다(한쪽 2 + 다른쪽 0).
            // claim 이 원자적이지 않으면 둘 다 2 를 등록해 합 4 · 위시 4 로 늘어난다.
            assertTrue(sizes.all { it >= 0 }, "두 confirm 스레드가 예외 없이 끝나야 한다: $sizes")
            assertEquals(2, sizes.sum(), "등록된 위시 합은 정확히 2 여야 한다: $sizes")
            assertEquals(2, wishCount(userId))
            assertEquals(0, pendingCount(userId))
            awaitDispatchSettled(userId)
        } finally {
            cleanupWishes(userId)
        }
    }

    @Test
    fun `confirm 과 폴링이 같은 pending 을 동시에 다퉈도 위시가 한 번만 등록된다`() {
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            seedExtractor()
            val keys = wishlistService.presignImageUploads(listOf("image/png"), userId).map { it.imageKey }
            makePollable(userId) // grace 를 통과시켜 폴링도 이 pending 을 대상으로 삼게 한다.

            val startLatch = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            val confirmFuture =
                executor.submit(
                    Callable {
                        startLatch.await()
                        runCatching { wishlistService.confirmImageRegistration(keys, userId).size }.getOrElse { -1 }
                    },
                )
            val pollFuture =
                executor.submit(
                    Callable {
                        startLatch.await()
                        runCatching { pollingScheduler.pollOnce() }.map { 0 }.getOrElse { -1 }
                    },
                )
            startLatch.countDown() // confirm·poll 동시 출발
            val confirmSize = confirmFuture.get(10, TimeUnit.SECONDS)
            val pollResult = pollFuture.get(10, TimeUnit.SECONDS)
            executor.shutdown()

            // confirm 이 이기면 1 개 등록, 폴링이 이기면 confirm 은 빈 목록(0). 어느 쪽이든 예외 없이 끝나야 한다.
            assertTrue(confirmSize in setOf(0, 1), "confirm 은 0 또는 1 개를 등록해야 한다: $confirmSize")
            assertEquals(0, pollResult, "폴링 스레드가 예외 없이 끝나야 한다")
            // 어느 writer 가 이겼든 위시는 정확히 1 개, pending 은 claim 돼 사라진다(중복 등록 없음).
            assertEquals(1, wishCount(userId))
            assertEquals(0, pendingCount(userId))
            awaitDispatchSettled(userId)
        } finally {
            cleanupWishes(userId)
        }
    }

    // ---- 헬퍼 ----

    private fun seedExtractor() {
        stubImageSnapshotExtractor.build = {
            ProductSnapshot(link = null, name = "상품", currentPrice = 1_000, currency = "KRW", imageUrl = "https://img.example.com/p.png")
        }
    }

    // 발급 직후 createdAt 은 now 라 POLL_GRACE 안이다. 폴링 대상이 되도록 발급 시각을 과거로 밀어 grace 를 통과시킨다.
    private fun makePollable(userId: UUID) {
        jdbcTemplate.update(
            "UPDATE pending_uploads SET created_at = ? WHERE user_id = ?",
            LocalDateTime.now().minusMinutes(1),
            uuidToBytes(userId),
        )
    }

    // confirm 이 만든 PENDING 을 자동 dispatch(1s)가 파싱 중일 수 있어, 삭제-vs-워커 UPDATE 레이스를 피하려면
    // 후속 처리가 terminal(READY/FAILED)로 끝난 뒤 cleanup 한다.
    private fun awaitDispatchSettled(userId: UUID) {
        await().atMost(Duration.ofSeconds(5)).until {
            val inFlight =
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM item_snapshots s JOIN wishes w ON w.snapshot_id = s.id " +
                        "WHERE w.user_id = ? AND s.status IN ('PENDING', 'PROCESSING')",
                    Int::class.java,
                    uuidToBytes(userId),
                ) ?: 0
            inFlight == 0
        }
    }

    private fun insertMember(userId: UUID) {
        jdbcTemplate.update(
            "INSERT INTO users (id, nickname, identity_type, created_at, updated_at) VALUES (?, ?, ?, NOW(6), NOW(6))",
            uuidToBytes(userId),
            userId.toString().take(10),
            "MEMBER",
        )
    }

    private fun wishCount(userId: UUID): Int =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wishes WHERE user_id = ?", Int::class.java, uuidToBytes(userId)) ?: 0

    private fun pendingCount(userId: UUID): Int =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pending_uploads WHERE user_id = ?", Int::class.java, uuidToBytes(userId)) ?: 0

    private fun cleanupWishes(userId: UUID) {
        val itemIds =
            jdbcTemplate.queryForList(
                "SELECT s.item_id FROM wishes w JOIN item_snapshots s ON s.id = w.snapshot_id WHERE w.user_id = ?",
                Long::class.java,
                uuidToBytes(userId),
            )
        jdbcTemplate.update("DELETE FROM wishes WHERE user_id = ?", uuidToBytes(userId))
        itemIds.takeIf { it.isNotEmpty() }?.let {
            jdbcTemplate.update("DELETE FROM item_snapshots WHERE item_id IN (${it.joinToString(",")})")
            jdbcTemplate.update("DELETE FROM items WHERE id IN (${it.joinToString(",")})")
        }
        jdbcTemplate.update("DELETE FROM pending_uploads WHERE user_id = ?", uuidToBytes(userId))
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", uuidToBytes(userId))
    }
}
