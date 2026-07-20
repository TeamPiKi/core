package com.depromeet.piki.wishlist.controller

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.repository.ItemRepository
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.wishlist.domain.Wish
import com.depromeet.piki.wishlist.repository.WishRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import java.time.LocalDateTime
import java.util.UUID

// 가격 히스토리 조회(#358 6단계)는 동기 조회라 @Transactional 자동 롤백으로 격리한다. 한 item 에 READY 버전을 직접 쌓아
// "갱신·새로고침이 누적된 상태"를 시딩하고, 응답 contract(최신순 정렬·READY 필터·isActive·activeSnapshotId·필드 매핑)를 고정한다.
@Transactional
class WishPriceHistoryIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var itemRepository: ItemRepository

    @Autowired
    private lateinit var itemSnapshotRepository: ItemSnapshotRepository

    @Autowired
    private lateinit var wishRepository: WishRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var jwtProvider: JwtProvider

    @Test
    fun `가격 히스토리를 조회하면 READY 버전이 최신순으로 내려가고 활성 버전에 isActive 가 표시된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val itemId = saveItem("https://shop.example.com/products/history")
        val older = saveReadySnapshot(itemId, "옛 상품", 119_000, LocalDateTime.of(2026, 5, 1, 10, 0))
        val middle = saveReadySnapshot(itemId, "중간 상품", 99_000, LocalDateTime.of(2026, 5, 15, 10, 0))
        val active = saveReadySnapshot(itemId, "현재 상품", 109_000, LocalDateTime.of(2026, 6, 1, 10, 0))
        val wishId = saveWish(userId, active) // 활성 포인터 = 최신 버전

        mockMvc
            .perform(
                get("/api/v1/wishlists/$wishId/history")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.itemId").value(itemId))
            .andExpect(jsonPath("$.data.sourceUrl").value("https://shop.example.com/products/history"))
            .andExpect(jsonPath("$.data.activeSnapshotId").value(active))
            .andExpect(jsonPath("$.data.entries.length()").value(3))
            // 최신순(snapshotId desc): active → middle → older
            .andExpect(jsonPath("$.data.entries[0].snapshotId").value(active))
            .andExpect(jsonPath("$.data.entries[0].currentPrice").value(109_000))
            .andExpect(jsonPath("$.data.entries[0].name").value("현재 상품"))
            .andExpect(jsonPath("$.data.entries[0].currency").value("KRW"))
            .andExpect(jsonPath("$.data.entries[0].isActive").value(true))
            .andExpect(jsonPath("$.data.entries[1].snapshotId").value(middle))
            .andExpect(jsonPath("$.data.entries[1].currentPrice").value(99_000))
            .andExpect(jsonPath("$.data.entries[1].isActive").value(false))
            .andExpect(jsonPath("$.data.entries[2].snapshotId").value(older))
            .andExpect(jsonPath("$.data.entries[2].currentPrice").value(119_000))
            .andExpect(jsonPath("$.data.entries[2].isActive").value(false))
    }

    @Test
    fun `가격 히스토리에는 READY 버전만 포함되고 PENDING·PROCESSING·FAILED 는 제외된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val itemId = saveItem("https://shop.example.com/products/mixed")
        val ready = saveReadySnapshot(itemId, "완성 버전", 50_000, LocalDateTime.now())
        // 같은 item 에 가격 없는 버전들을 섞어 둔다 — 히스토리에서 빠져야 한다.
        itemSnapshotRepository.save(ItemSnapshot.pending(itemId))
        itemSnapshotRepository.save(ItemSnapshot.pending(itemId).apply { markProcessing() })
        itemSnapshotRepository.save(ItemSnapshot(itemId = itemId, status = ItemStatus.FAILED))
        val wishId = saveWish(userId, ready)

        mockMvc
            .perform(
                get("/api/v1/wishlists/$wishId/history")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.entries.length()").value(1))
            .andExpect(jsonPath("$.data.entries[0].snapshotId").value(ready))
            .andExpect(jsonPath("$.data.entries[0].name").value("완성 버전"))
    }

    @Test
    fun `soft-delete 된 READY snapshot 은 가격 히스토리에서 제외된다`() {
        // production 쿼리의 deletedAt IS NULL 필터 회귀 방지 — status 필터와 별개로 삭제된 행이 새지 않는지 고정한다.
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val itemId = saveItem("https://shop.example.com/products/soft-deleted")
        val live = saveReadySnapshot(itemId, "살아있는 버전", 30_000, LocalDateTime.now())
        // 같은 item 에 soft-delete 된 READY 버전을 섞는다 — deletedAt 이 채워져 히스토리에서 빠져야 한다.
        itemSnapshotRepository.save(
            ItemSnapshot(
                itemId = itemId,
                name = "삭제된 버전",
                currentPrice = 99_000,
                currency = "KRW",
                imageUrl = "https://cdn.example.com/p/deleted.jpg",
                status = ItemStatus.READY,
                extractedAt = LocalDateTime.now(),
            ).apply { deletedAt = LocalDateTime.now() },
        )
        val wishId = saveWish(userId, live)

        mockMvc
            .perform(
                get("/api/v1/wishlists/$wishId/history")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.entries.length()").value(1))
            .andExpect(jsonPath("$.data.entries[0].snapshotId").value(live))
            .andExpect(jsonPath("$.data.entries[0].name").value("살아있는 버전"))
    }

    @Test
    fun `아직 추출 성공 이력이 없으면 빈 히스토리를 반환한다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val itemId = saveItem("https://shop.example.com/products/pending-only")
        // 활성 버전이 아직 PENDING — READY 가 하나도 없다.
        val pending = itemSnapshotRepository.save(ItemSnapshot.pending(itemId)).getId()
        val wishId = saveWish(userId, pending)

        mockMvc
            .perform(
                get("/api/v1/wishlists/$wishId/history")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.activeSnapshotId").value(pending))
            .andExpect(jsonPath("$.data.entries.length()").value(0))
    }

    @Test
    fun `남의 위시 가격 히스토리를 조회하면 403 으로 거부된다`() {
        val mockMvc = buildMockMvc()
        val ownerId = UUID.randomUUID()
        val otherId = UUID.randomUUID()
        insertMember(ownerId)
        insertMember(otherId)
        val itemId = saveItem("https://shop.example.com/products/owned")
        val snapshot = saveReadySnapshot(itemId, "내 상품", 10_000, LocalDateTime.now())
        val wishId = saveWish(ownerId, snapshot)

        mockMvc
            .perform(
                get("/api/v1/wishlists/$wishId/history")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(otherId)}"),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.detail").value("내 위시 아이템만 볼 수 있어요."))
    }

    @Test
    fun `존재하지 않는 위시의 가격 히스토리를 조회하면 404 가 반환된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)

        mockMvc
            .perform(
                get("/api/v1/wishlists/99999999/history")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
            ).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.detail").value("이미 삭제된 아이템이에요."))
    }

    @Test
    fun `탈퇴한 회원의 살아있는 access token 으로 가격 히스토리를 조회하면 409 가 반환된다`() {
        // 소유권 검증(verifyOwnedBy)만으로는 탈퇴 여부를 못 본다 — 본인 위시이므로 그대로 통과해버린다.
        // 회원 가드가 활성 조회로 끊어야 탈퇴 회원이 자기 가격 이력을 계속 읽는 것을 막는다 (#691).
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val itemId = saveItem("https://shop.example.com/products/withdrawn")
        val snapshot = saveReadySnapshot(itemId, "내 상품", 10_000, LocalDateTime.now())
        val wishId = saveWish(userId, snapshot)
        jdbcTemplate.update("UPDATE users SET deleted_at = NOW(6) WHERE id = ?", uuidToBytes(userId))

        mockMvc
            .perform(
                get("/api/v1/wishlists/$wishId/history")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("USER-003"))
            .andExpect(jsonPath("$.detail").value("탈퇴한 계정이에요."))
    }

    @Test
    fun `미인증으로 가격 히스토리를 조회하면 401 이다`() {
        val mockMvc = buildMockMvc()
        mockMvc
            .perform(get("/api/v1/wishlists/1/history"))
            .andExpect(status().isUnauthorized)
    }

    private fun saveItem(url: String): Long = itemRepository.save(Item(ProductLink.parse(url))).getId()

    private fun saveReadySnapshot(
        itemId: Long,
        name: String,
        price: Int,
        extractedAt: LocalDateTime,
    ): Long =
        itemSnapshotRepository
            .save(
                ItemSnapshot(
                    itemId = itemId,
                    name = name,
                    currentPrice = price,
                    currency = "KRW",
                    imageUrl = "https://cdn.example.com/p/$price.jpg",
                    status = ItemStatus.READY,
                    extractedAt = extractedAt,
                ),
            ).getId()

    private fun saveWish(
        userId: UUID,
        snapshotId: Long,
    ): Long = wishRepository.save(Wish(userId = userId, snapshotId = snapshotId)).getId()

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
}
