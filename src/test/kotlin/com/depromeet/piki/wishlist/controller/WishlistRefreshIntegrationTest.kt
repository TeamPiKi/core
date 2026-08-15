package com.depromeet.piki.wishlist.controller

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.ItemSnapshotSource
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.repository.ItemRepository
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.item.service.ItemParsingService
import com.depromeet.piki.item.service.ParsingOwnership
import com.depromeet.piki.notification.domain.NotificationType
import com.depromeet.piki.notification.repository.NotificationRepository
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubProductLinkExtractor
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.tournament.domain.TournamentItem
import com.depromeet.piki.tournament.repository.TournamentItemJpaRepository
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.wishlist.domain.Wish
import com.depromeet.piki.wishlist.repository.WishRepository
import com.depromeet.piki.wishlist.service.WishPersistenceService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.awaitility.Awaitility.await
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

// 위시 새로고침(#358)은 등록과 같은 비동기 작업 큐 흐름이다 — 새 PENDING snapshot 을 적재하고 활성 포인터를 즉시 스왑한 뒤
// 디스패처(@Scheduled)가 집어 READY/FAILED 로 전이한다. @Transactional 자동 롤백으로는 워커(별도 스레드·새 트랜잭션)가
// 미커밋 데이터를 못 보므로, 여기서는 실제 커밋하고 Awaitility 로 전이를 기다린다. 자기가 만든 행은 격리 userId 로 정리한다.
class WishlistRefreshIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var stubProductLinkExtractor: StubProductLinkExtractor

    @Autowired
    private lateinit var itemRepository: ItemRepository

    @Autowired
    private lateinit var itemSnapshotRepository: ItemSnapshotRepository

    @Autowired
    private lateinit var wishRepository: WishRepository

    @Autowired
    private lateinit var tournamentItemJpaRepository: TournamentItemJpaRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var jwtProvider: JwtProvider

    @Autowired
    private lateinit var itemParsingService: ItemParsingService

    @Autowired
    private lateinit var parsingOwnership: ParsingOwnership

    @Autowired
    private lateinit var wishPersistenceService: WishPersistenceService

    @Autowired
    private lateinit var notificationRepository: NotificationRepository

    @Test
    fun `READY 위시를 새로고침하면 새 PENDING 버전이 활성이 되고 추출 성공 시 READY 로 전이한다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            stubProductLinkExtractor.build = {
                ProductSnapshot(link = it, name = "새 상품", price = 20_000, currency = "KRW", imageUrl = "https://img.example.com/a.png")
            }
            val (wishId, itemId, oldSnapshotId) = seedReadyWish(userId, "https://shop.example.com/products/refresh", "옛 상품", 10_000)

            // 새로고침 — 같은 item 의 새 PENDING 버전이 즉시 활성이 된다(등록과 동일 비동기 전이 모델: 완료는 SSE 알림으로 통보).
            mockMvc
                .perform(
                    post("/api/v1/wishlists/$wishId/refresh")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.data.item.id").value(itemId))
                .andExpect(jsonPath("$.data.item.status").value("PENDING"))

            // 디스패처가 집어 추출 성공 → READY 전이, 새 값으로 채워진다.
            await().atMost(Duration.ofSeconds(5)).until { latestSnapshot(itemId)?.status == ItemStatus.READY }
            val active = latestSnapshot(itemId) ?: error("item $itemId 의 snapshot 이 없다")
            assertEquals("새 상품", active.name)
            assertEquals(20_000, active.price)
            assertNotEquals(oldSnapshotId, active.getId()) // 새 버전 행

            // 옛 snapshot 행은 보존된다(토너먼트 출전 격리의 근거) — 여전히 READY, 옛 값.
            val preserved = itemSnapshotRepository.findById(oldSnapshotId) ?: error("옛 snapshot 이 사라졌다")
            assertEquals(ItemStatus.READY, preserved.status)
            assertEquals("옛 상품", preserved.name)

            // wish 활성 포인터가 새 버전으로 스왑됐다.
            assertEquals(active.getId(), wishRepository.findById(wishId)?.snapshotId)
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `이미 진행 중인 위시를 새로고침하면 새 추출을 만들지 않고 현재 진행 상태를 그대로 반환한다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            // PROCESSING 위시 시딩 — 디스패처는 PENDING 만 집고 recover 는 stale(60초)만 보므로 방금 만든 행은 PROCESSING 고정.
            val item = itemRepository.save(Item(ProductLink.parse("https://shop.example.com/products/inprogress")))
            val snapshot = itemSnapshotRepository.save(ItemSnapshot.pending(item.getId()).apply { markProcessing() })
            val wish = wishRepository.save(Wish(userId = userId, snapshotId = snapshot.getId()))
            val itemId = item.getId()
            val before = countSnapshots(itemId)

            mockMvc
                .perform(
                    post("/api/v1/wishlists/${wish.getId()}/refresh")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.data.item.status").value("PROCESSING"))

            // 멱등 — 새 snapshot 행이 생기지 않고 활성 포인터도 그대로다.
            assertEquals(before, countSnapshots(itemId))
            assertEquals(snapshot.getId(), wishRepository.findById(wish.getId())?.snapshotId)
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `링크 없는 이미지 위시를 새로고침하면 400 으로 거부된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            // 이미지로 등록한 item 은 link 가 없어 재추출 입력이 없다.
            val item = itemRepository.save(Item(link = null))
            val snapshot =
                itemSnapshotRepository.save(
                    ItemSnapshot(
                        itemId = item.getId(),
                        name = "이미지 상품",
                        price = 5_000,
                        currency = "KRW",
                        status = ItemStatus.READY,
                        extractedAt = LocalDateTime.now(),
                    ),
                )
            val wish = wishRepository.save(Wish(userId = userId, snapshotId = snapshot.getId()))

            mockMvc
                .perform(
                    post("/api/v1/wishlists/${wish.getId()}/refresh")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("WISH-007"))
                .andExpect(jsonPath("$.detail").value("링크가 없는 항목은 새로고침할 수 없습니다."))
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `남의 위시를 새로고침하면 403 으로 거부된다`() {
        val mockMvc = buildMockMvc()
        val owner = UUID.randomUUID()
        insertMember(owner)
        // 회원 가드(requireMember)가 findById 로 identityType 을 보므로 attacker 도 실제 회원 행이 있어야
        // 가드를 통과해 소유권 검증(403)까지 닿는다. 행이 없으면 가드가 먼저 404(notFound)로 막는다.
        val attacker = UUID.randomUUID()
        insertMember(attacker)
        try {
            val (wishId, _, _) = seedReadyWish(owner, "https://shop.example.com/products/owned", "옛 상품", 10_000)

            mockMvc
                .perform(
                    post("/api/v1/wishlists/$wishId/refresh")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(attacker)}"),
                ).andExpect(status().isForbidden)
        } finally {
            cleanup(owner)
            cleanup(attacker)
        }
    }

    @Test
    fun `존재하지 않는 위시를 새로고침하면 404 다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            mockMvc
                .perform(
                    post("/api/v1/wishlists/999999999/refresh")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
                ).andExpect(status().isNotFound)
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `미인증으로 새로고침하면 401 이다`() {
        val mockMvc = buildMockMvc()
        mockMvc
            .perform(post("/api/v1/wishlists/1/refresh"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `추출 실패(FAILED) 위시를 새로고침하면 409 로 거부된다`() {
        // 새로고침은 성공(READY) 항목의 재추출 전용 — 추출 실패(FAILED) 항목은 보정(recover)으로 복구한다.
        // 보정(FAILED)과 새로고침(READY)이 상태로 갈려, recover-vs-refresh 동시 요청이 서로의 활성 포인터를 침범하지 않는다.
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            val item = itemRepository.save(Item(ProductLink.parse("https://shop.example.com/products/failed")))
            val snapshot = itemSnapshotRepository.save(ItemSnapshot(itemId = item.getId(), status = ItemStatus.FAILED))
            val wish = wishRepository.save(Wish(userId = userId, snapshotId = snapshot.getId()))

            mockMvc
                .perform(
                    post("/api/v1/wishlists/${wish.getId()}/refresh")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("WISH-008"))
                .andExpect(jsonPath("$.detail").value("추출에 실패한 항목은 새로고침 대신 정보를 직접 입력해 복구해 주세요."))
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `토너먼트에 출전한 위시를 새로고침해도 출전 시점 snapshot 은 바뀌지 않는다`() {
        // #362 의 핵심 회귀 — 위시 갱신이 tournament_item 의 고정 snapshot 을 침범하면 출전 공정성이 깨진다.
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            stubProductLinkExtractor.build = {
                ProductSnapshot(link = it, name = "새 상품", price = 20_000, currency = "KRW", imageUrl = "https://img.example.com/a.png")
            }
            val (wishId, itemId, oldSnapshotId) = seedReadyWish(userId, "https://shop.example.com/products/inplay", "옛 상품", 10_000)
            // 출전 시점 고정 — tournament_item 이 옛 snapshot 을 가리킨다(FK 없으니 tournament 행 없이도 격리만 검증).
            val tournamentItem =
                tournamentItemJpaRepository.save(
                    TournamentItem(tournamentId = 999_999L, userId = userId, snapshotId = oldSnapshotId),
                )

            mockMvc
                .perform(
                    post("/api/v1/wishlists/$wishId/refresh")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
                ).andExpect(status().isOk)

            await().atMost(Duration.ofSeconds(5)).until { latestSnapshot(itemId)?.status == ItemStatus.READY }

            // wish 활성은 새 버전으로 스왑됐지만,
            assertNotEquals(oldSnapshotId, wishRepository.findById(wishId)?.snapshotId)
            // tournament_item 은 출전 시점 snapshot 에 고정돼 그대로다.
            val fixedSnapshotId =
                jdbcTemplate.queryForObject(
                    "SELECT snapshot_id FROM tournament_items WHERE id = ?",
                    Long::class.java,
                    tournamentItem.getId(),
                )
            assertEquals(oldSnapshotId, fixedSnapshotId)
            // 옛 snapshot 행·값도 보존된다.
            val preserved = itemSnapshotRepository.findById(oldSnapshotId) ?: error("옛 snapshot 이 사라졌다")
            assertEquals("옛 상품", preserved.name)
            assertEquals(ItemStatus.READY, preserved.status)
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `위시를 새로고침해 파싱이 완료되면 본인에게 완료 알림이 발행된다`() {
        // refresh 도 등록과 동일한 markReady→ItemParsingCompleted 경로를 타므로, 갱신 완료 시 위시 주인(본인)에게
        // 완료 알림(ITEM_PARSING_COMPLETED, 등록·갱신 공용 문구)이 발행된다. 그 발행을 end-to-end 로 고정한다.
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            stubProductLinkExtractor.build = {
                ProductSnapshot(link = it, name = "새 상품", price = 20_000, currency = "KRW", imageUrl = "https://img.example.com/a.png")
            }
            val (wishId, itemId, _) = seedReadyWish(userId, "https://shop.example.com/products/refresh-notify", "옛 상품", 10_000)

            mockMvc
                .perform(
                    post("/api/v1/wishlists/$wishId/refresh")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
                ).andExpect(status().isOk)

            await().atMost(Duration.ofSeconds(5)).until { latestSnapshot(itemId)?.status == ItemStatus.READY }
            // 완료 알림이 본인에게 저장됐다 (markReady 의 AFTER_COMMIT 리스너 → 비동기 저장이라 await).
            await().atMost(Duration.ofSeconds(5)).until {
                notificationRepository
                    .findPage(userId, cursor = null, limit = 10)
                    .any { it.type == NotificationType.ITEM_PARSING_COMPLETED }
            }
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `markExtracted 는 claim 한 snapshot 만 전이하고 같은 item 의 다른 진행 중 버전은 건드리지 않는다`() {
        // F2 회귀 — 갱신은 한 item 에 여러 진행 중 snapshot 을 만든다. 전이가 findLatestByItemId(최신)가 아니라
        // claim 한 snapshotId 를 짚어야, stale·좀비 워커가 다른(새) 버전을 오전이하지 않는다.
        val userId = UUID.randomUUID()
        insertMember(userId)
        val item = itemRepository.save(Item(ProductLink.parse("https://shop.example.com/products/two-versions")))
        try {
            val v1 = itemSnapshotRepository.save(ItemSnapshot.pending(item.getId()).apply { markProcessing() })
            val v2 = itemSnapshotRepository.save(ItemSnapshot.pending(item.getId()).apply { markProcessing() })

            // v1(더 낮은 id, 최신 아님)을 지정해 전이 — findLatest 였다면 v2 가 전이됐을 것이다.
            // 집기는 attempt 를 안 올리므로 워커의 소유권 획득(0 -> 1)을 재현한 뒤 그 토큰으로 전이한다.
            val attempt = parsingOwnership.acquire(v1.getId(), 0) ?: error("소유권 획득 실패")
            itemParsingService.markExtracted(
                v1.getId(),
                ProductSnapshot(link = null, name = "버전1", price = 100, currency = "KRW", imageUrl = "https://img.example.com/a.png"),
                expectedAttempt = attempt,
            )

            assertEquals(ItemStatus.READY, itemSnapshotRepository.findById(v1.getId())?.status)
            assertEquals("버전1", itemSnapshotRepository.findById(v1.getId())?.name)
            // 최신(v2)은 그대로 PROCESSING — claim 한 행만 정확히 전이됐다.
            assertEquals(ItemStatus.PROCESSING, itemSnapshotRepository.findById(v2.getId())?.status)
            assertNull(itemSnapshotRepository.findById(v2.getId())?.name)
        } finally {
            jdbcTemplate.update("DELETE FROM item_snapshots WHERE item_id = ?", item.getId())
            jdbcTemplate.update("DELETE FROM items WHERE id = ?", item.getId())
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", uuidToBytes(userId))
        }
    }

    @Test
    fun `manualEdit 은 위시 활성 버전을 base 로 MANUAL 새 버전을 쌓아 스왑하고 기존 행들은 건드리지 않는다`() {
        // 수기 수정(#825 결정 4) 회귀 — 활성(FAILED) 버전이 최신이 아니어도 base 는 wish 활성 포인터가 결정하고,
        // 수정은 기존 행을 고치지 않으므로 FAILED 행도 같은 item 의 최신(PROCESSING) 행도 그대로 남는다.
        val userId = UUID.randomUUID()
        insertMember(userId)
        val item = itemRepository.save(Item(ProductLink.parse("https://shop.example.com/products/recover-version")))
        try {
            val failed =
                itemSnapshotRepository.save(
                    ItemSnapshot.pending(item.getId()).apply {
                        markProcessing()
                        markFailed()
                    },
                )
            val newer = itemSnapshotRepository.save(ItemSnapshot.pending(item.getId()).apply { markProcessing() })
            val wish = wishRepository.save(Wish(userId, failed.getId()))

            val result =
                wishPersistenceService.manualEdit(
                    userId = userId,
                    wishId = wish.getId(),
                    name = "보정",
                    price = 200,
                    imageUrl = "https://img.example.com/a.png",
                    currency = "KRW",
                    memo = null,
                )

            // 새 MANUAL 버전이 활성으로 스왑됐다 — 편집자·출처가 박힌다.
            val active =
                itemSnapshotRepository.findById(wishRepository.findById(wish.getId())!!.snapshotId)
                    ?: error("활성 snapshot 이 없다")
            assertEquals(result.snapshot.getId(), active.getId())
            assertEquals(ItemStatus.READY, active.status)
            assertEquals("보정", active.name)
            assertEquals(ItemSnapshotSource.MANUAL, active.source)
            assertEquals(userId, active.editedBy)
            // 기존 행들은 불변 — FAILED 는 FAILED 로(이력), 최신 PROCESSING 은 파싱 계속.
            assertEquals(ItemStatus.FAILED, itemSnapshotRepository.findById(failed.getId())?.status)
            assertEquals(ItemStatus.PROCESSING, itemSnapshotRepository.findById(newer.getId())?.status)
        } finally {
            jdbcTemplate.update("DELETE FROM wishes WHERE user_id = ?", uuidToBytes(userId))
            jdbcTemplate.update("DELETE FROM item_snapshots WHERE item_id = ?", item.getId())
            jdbcTemplate.update("DELETE FROM items WHERE id = ?", item.getId())
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", uuidToBytes(userId))
        }
    }

    // READY 상태의 link 보유 위시를 직접 시딩한다. (wishId, itemId, snapshotId) 반환.
    private fun seedReadyWish(
        userId: UUID,
        url: String,
        name: String,
        price: Int,
    ): Triple<Long, Long, Long> {
        val item = itemRepository.save(Item(ProductLink.parse(url)))
        val snapshot =
            itemSnapshotRepository.save(
                ItemSnapshot(
                    itemId = item.getId(),
                    name = name,
                    price = price,
                    currency = "KRW",
                    status = ItemStatus.READY,
                    extractedAt = LocalDateTime.now(),
                ),
            )
        val wish = wishRepository.save(Wish(userId = userId, snapshotId = snapshot.getId()))
        return Triple(wish.getId(), item.getId(), snapshot.getId())
    }

    private fun buildMockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    private fun insertMember(userId: UUID) {
        jdbcTemplate.update(
            "INSERT INTO users (id, nickname, identity_type, created_at, updated_at) VALUES (?, ?, ?, NOW(6), NOW(6))",
            uuidToBytes(userId),
            userId.toString().take(10),
            "MEMBER",
        )
    }

    private fun memberToken(userId: UUID): String = jwtProvider.generateAccessToken(userId, IdentityType.MEMBER)

    private fun latestSnapshot(itemId: Long): ItemSnapshot? = itemSnapshotRepository.findLatestByItemId(itemId)

    private fun countSnapshots(itemId: Long): Int =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM item_snapshots WHERE item_id = ?", Int::class.java, itemId) ?: 0

    // @Transactional 자동 롤백이 없으므로 이 테스트가 만든 행을 직접 정리한다. wishes 는 snapshot_id 만 들어(4b)
    // item_snapshots 를 조인해 itemId 에 도달하고, tournament_item 은 snapshot_id 로 그 itemId 의 버전들을 가리킨다.
    private fun cleanup(userId: UUID) {
        val itemIds =
            jdbcTemplate.queryForList(
                "SELECT s.item_id FROM wishes w JOIN item_snapshots s ON s.id = w.snapshot_id WHERE w.user_id = ?",
                Long::class.java,
                uuidToBytes(userId),
            )
        jdbcTemplate.update("DELETE FROM wishes WHERE user_id = ?", uuidToBytes(userId))
        itemIds.takeIf { it.isNotEmpty() }?.let {
            val ph = it.joinToString(",")
            jdbcTemplate.update("DELETE FROM tournament_items WHERE snapshot_id IN (SELECT id FROM item_snapshots WHERE item_id IN ($ph))")
            jdbcTemplate.update("DELETE FROM item_snapshots WHERE item_id IN ($ph)")
            jdbcTemplate.update("DELETE FROM items WHERE id IN ($ph)")
        }
        jdbcTemplate.update("DELETE FROM notifications WHERE user_id = ?", uuidToBytes(userId))
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", uuidToBytes(userId))
    }
}
