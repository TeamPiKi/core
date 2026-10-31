package com.depromeet.piki.wishlist.controller

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.common.outbox.OutboxStatus
import com.depromeet.piki.item.domain.ItemParseOutbox
import com.depromeet.piki.item.domain.ItemSlot
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.ParseInput
import com.depromeet.piki.item.domain.ParsePurpose
import com.depromeet.piki.item.repository.ItemParseOutboxRepository
import com.depromeet.piki.item.repository.ItemRepository
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.item.service.ItemParsingScheduler
import com.depromeet.piki.notification.domain.NotificationType
import com.depromeet.piki.notification.repository.NotificationRepository
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubLinkSnapshotExtractor
import com.depromeet.piki.support.awaitTicking
import com.depromeet.piki.support.deleteWishDataOf
import com.depromeet.piki.support.failedBeforeSave
import com.depromeet.piki.support.imageItem
import com.depromeet.piki.support.itemVersion
import com.depromeet.piki.support.linkItem
import com.depromeet.piki.support.parseOutboxStatusOf
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.wishlist.domain.Wish
import com.depromeet.piki.wishlist.repository.WishRepository
import org.awaitility.Awaitility.await
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// @Async 파서는 별도 트랜잭션이라 커밋 후 awaitTicking 으로 기다림
class WishlistRefreshIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var stubLinkSnapshotExtractor: StubLinkSnapshotExtractor

    @Autowired
    private lateinit var itemRepository: ItemRepository

    @Autowired
    private lateinit var itemSnapshotRepository: ItemSnapshotRepository

    @Autowired
    private lateinit var itemParseOutboxRepository: ItemParseOutboxRepository

    @Autowired
    private lateinit var wishRepository: WishRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var jwtProvider: JwtProvider

    @Autowired
    private lateinit var itemParsingScheduler: ItemParsingScheduler

    @Autowired
    private lateinit var notificationRepository: NotificationRepository

    @Test
    fun `READY 위시를 새로고침하면 값은 그대로 refreshing 이 켜지고 성공하면 같은 아이템에 새 버전이 쌓인다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            stubLinkSnapshotExtractor.build = {
                ProductSnapshot(name = "새 상품", price = 20_000, currency = "KRW", imageUrl = "https://img.example.com/a.png")
            }
            val (wishId, itemId, oldVersionId) = saveReadyWish(userId, "https://shop.example.com/products/refresh", "옛 상품", 10_000)

            mockMvc
                .perform(
                    post("/api/v1/wishlists/$wishId/refresh")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.data.item.id").value(itemId))
                .andExpect(jsonPath("$.data.item.status").value("READY"))
                .andExpect(jsonPath("$.data.item.refreshing").value(true))
                .andExpect(jsonPath("$.data.item.name").value("옛 상품"))
                .andExpect(jsonPath("$.data.item.price").value(10_000))

            val slot = ItemSlot.wish(wishId)
            itemParsingScheduler.awaitTicking { jdbcTemplate.parseOutboxStatusOf(slot) == OutboxStatus.SUCCEEDED }

            val versions = versionsOf(itemId)
            assertEquals(2, versions.size)
            assertEquals(listOf("옛 상품", "새 상품"), versions.sortedBy { it.getId() }.map { it.name })
            assertEquals(oldVersionId, versions.minBy { it.getId() }.getId())
            assertEquals(itemId, wishRepository.findById(wishId)?.itemId)

            mockMvc
                .perform(get("/api/v1/wishlists/$wishId").header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.item.refreshing").value(false))
                .andExpect(jsonPath("$.data.item.name").value("새 상품"))
                .andExpect(jsonPath("$.data.item.price").value(20_000))
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `새로고침이 진행 중인 위시를 다시 새로고침하면 요청을 더 쌓지 않고 진행 상태를 그대로 반환한다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            val (wishId, itemId, _) = saveReadyWish(userId, "https://shop.example.com/products/inprogress", "옛 상품", 10_000)
            repeat(2) {
                mockMvc
                    .perform(
                        post("/api/v1/wishlists/$wishId/refresh")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
                    ).andExpect(status().isOk)
                    .andExpect(jsonPath("$.data.item.id").value(itemId))
                    .andExpect(jsonPath("$.data.item.refreshing").value(true))
                    .andExpect(jsonPath("$.data.item.name").value("옛 상품"))
            }

            assertEquals(1, countParseOutbox(ItemSlot.wish(wishId)))
            assertEquals(1, versionsOf(itemId).size)
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
            val item = itemRepository.save(imageItem())
            itemSnapshotRepository.save(itemVersion(item.getId(), name = "이미지 상품", price = 5_000, createdBy = userId))
            val wish = wishRepository.save(Wish(userId = userId, itemId = item.getId()))

            mockMvc
                .perform(
                    post("/api/v1/wishlists/${wish.getId()}/refresh")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("ITEM-007"))
                .andExpect(jsonPath("$.detail").value("링크로 등록한 상품만 새로고침할 수 있어요."))
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `남의 위시를 새로고침하면 403 으로 거부된다`() {
        val mockMvc = buildMockMvc()
        val owner = UUID.randomUUID()
        insertMember(owner)
        // 회원 가드가 먼저 회원 행을 보므로 attacker 도 회원이어야 소유권 검증(403)까지 닿는다
        val attacker = UUID.randomUUID()
        insertMember(attacker)
        try {
            val (wishId, _, _) = saveReadyWish(owner, "https://shop.example.com/products/owned", "옛 상품", 10_000)

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
    fun `아이템이 확정되지 못한 실패 위시를 새로고침하면 409 로 거부되고 요청이 쌓이지 않는다`() {
        // 재추출해도 결정론적으로 다시 실패해 사용자 입력으로 유도한다
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            val url = "https://shop.example.com/products/failed"
            val wish = wishRepository.save(Wish(userId = userId, itemId = null))
            itemParseOutboxRepository.save(failedOutbox(wish.slot, url))

            mockMvc
                .perform(
                    post("/api/v1/wishlists/${wish.getId()}/refresh")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("WISH-008"))
                .andExpect(jsonPath("$.detail").value("추출에 실패한 항목은 새로고침 대신 정보를 직접 입력해 복구해 주세요."))

            assertEquals(1, countParseOutbox(wish.slot))
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `직전 새로고침이 실패했어도 아이템이 있는 위시는 다시 새로고침된다`() {
        // 409 는 아이템이 없는 실패 위시에만 걸린다. 위 테스트의 대조군
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            val url = "https://shop.example.com/products/refresh-failed-before"
            val (wishId, itemId, _) = saveReadyWish(userId, url, "옛 상품", 10_000)
            val slot = ItemSlot.wish(wishId)
            itemParseOutboxRepository.save(failedOutbox(slot, url))

            mockMvc
                .perform(
                    post("/api/v1/wishlists/$wishId/refresh")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.data.item.id").value(itemId))
                .andExpect(jsonPath("$.data.item.status").value("READY"))
                .andExpect(jsonPath("$.data.item.refreshing").value(true))

            assertEquals(2, countParseOutbox(slot))
            assertEquals(OutboxStatus.PENDING, jdbcTemplate.parseOutboxStatusOf(slot))
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `위시를 새로고침해 파싱이 완료되면 본인에게 새로고침 완료 알림만 발행된다`() {
        // 등록 완료와 새로고침 완료는 요청 시점에 아이템이 있었는지로 갈린다(#1036). HTTP 에서 리스너까지 고정한다
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            stubLinkSnapshotExtractor.build = {
                ProductSnapshot(name = "새 상품", price = 20_000, currency = "KRW", imageUrl = "https://img.example.com/a.png")
            }
            val (wishId, _, _) = saveReadyWish(userId, "https://shop.example.com/products/refresh-notify", "옛 상품", 10_000)

            mockMvc
                .perform(
                    post("/api/v1/wishlists/$wishId/refresh")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
                ).andExpect(status().isOk)

            itemParsingScheduler.awaitTicking {
                jdbcTemplate.parseOutboxStatusOf(ItemSlot.wish(wishId)) == OutboxStatus.SUCCEEDED
            }
            // AFTER_COMMIT 리스너가 비동기로 저장한다
            await().atMost(Duration.ofSeconds(5)).until {
                notificationRepository
                    .findPage(userId, cursor = null, limit = 10)
                    .any { it.type == NotificationType.ITEM_REFRESH_COMPLETED }
            }
            assertEquals(
                listOf(NotificationType.ITEM_REFRESH_COMPLETED),
                notificationRepository.findPage(userId, cursor = null, limit = 10).map { it.type },
            )
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `새로고침 중인 위시는 사용자 입력이 409 이고 버전과 진행 중인 새로고침이 그대로다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            val url = "https://shop.example.com/products/input-while-refreshing"
            val (wishId, itemId, _) = saveReadyWish(userId, url, "기계 값", 10_000)
            val slot = ItemSlot.wish(wishId)
            itemParseOutboxRepository.save(ItemParseOutbox.of(slot, ParsePurpose.REFRESH, ParseInput.Link(ProductLink.parse(url))))

            mockMvc
                .perform(
                    multipart("/api/v1/wishlists/$wishId")
                        .param("name", "직접 입력한 이름")
                        .with {
                            it.method = "PATCH"
                            it
                        }.header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("ITEM-008"))

            assertEquals(listOf("기계 값"), versionsOf(itemId).map { it.name })
            assertEquals(OutboxStatus.PENDING, jdbcTemplate.parseOutboxStatusOf(slot))
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `등록 파싱 중인 위시를 새로고침하면 409 없이 아이템 없는 PARSING 응답을 그대로 돌려준다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        try {
            val url = "https://shop.example.com/products/refresh-parsing"
            val wish = wishRepository.save(Wish(userId = userId, itemId = null))
            itemParseOutboxRepository.save(ItemParseOutbox.of(wish.slot, ParsePurpose.REGISTER, ParseInput.Link(ProductLink.parse(url))))

            mockMvc
                .perform(
                    post("/api/v1/wishlists/${wish.getId()}/refresh")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.data.item.id").value(nullValue()))
                .andExpect(jsonPath("$.data.item.status").value("PARSING"))
                .andExpect(jsonPath("$.data.item.refreshing").value(false))
                .andExpect(jsonPath("$.data.item.sourceUrl").value(url))

            assertEquals(1, countParseOutbox(wish.slot))
        } finally {
            cleanup(userId)
        }
    }

    // 파싱 경로를 거치지 않고 행만 저장한다
    private fun saveReadyWish(
        userId: UUID,
        url: String,
        name: String,
        price: Int,
    ): SavedWish {
        val item = itemRepository.save(linkItem(url))
        val version = itemSnapshotRepository.save(itemVersion(item.getId(), name = name, price = price, createdBy = userId))
        val wish = wishRepository.save(Wish(userId = userId, itemId = item.getId()))
        return SavedWish(wishId = wish.getId(), itemId = item.getId(), versionId = version.getId())
    }

    private data class SavedWish(
        val wishId: Long,
        val itemId: Long,
        val versionId: Long,
    )

    private fun failedOutbox(
        slot: ItemSlot,
        url: String,
    ): ItemParseOutbox =
        ItemParseOutbox.of(slot, ParsePurpose.REGISTER, ParseInput.Link(ProductLink.parse(url))).failedBeforeSave()

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

    private fun versionsOf(itemId: Long): List<ItemSnapshot> = itemSnapshotRepository.findAllByItemIds(listOf(itemId))

    private fun countParseOutbox(slot: ItemSlot): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM item_parse_outbox WHERE slot_type = ? AND slot_id = ?",
            Int::class.java,
            slot.type.name,
            slot.id,
        ) ?: 0

    private fun cleanup(userId: UUID) {
        jdbcTemplate.deleteWishDataOf(userId)
        jdbcTemplate.update("DELETE FROM notifications WHERE user_id = ?", uuidToBytes(userId))
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", uuidToBytes(userId))
    }
}
