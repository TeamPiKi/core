package com.depromeet.piki.wishlist.controller

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.contracts.extraction.v1.ExtractionMethod
import com.depromeet.piki.item.domain.ItemParseOutbox
import com.depromeet.piki.item.domain.ParseInput
import com.depromeet.piki.item.domain.ParsePurpose
import com.depromeet.piki.item.domain.ItemSlot
import com.depromeet.piki.item.repository.ItemParseOutboxRepository
import com.depromeet.piki.item.repository.ItemRepository
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.itemVersion
import com.depromeet.piki.support.linkItem
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.wishlist.domain.Wish
import com.depromeet.piki.wishlist.repository.WishRepository
import jakarta.persistence.EntityManager
import org.hamcrest.Matchers.nullValue
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

// item 은 맥락 스코프를 거친 표시값이고 priceHistory 는 그 필터를 타지 않아, 이력 첫 항목이 표시값과 다를 수 있다
@Transactional
class WishPriceHistoryIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

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
    private lateinit var entityManager: EntityManager

    @Test
    fun `상세 조회하면 표시값과 가격 이력이 함께 내려가고 이력은 최신순이다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val itemId = saveItem("https://shop.example.com/products/history")
        saveMachineReady(itemId, "옛 상품", 119_000, LocalDateTime.of(2026, 5, 1, 10, 0))
        saveMachineReady(itemId, "중간 상품", 99_000, LocalDateTime.of(2026, 5, 15, 10, 0))
        saveMachineReady(itemId, "현재 상품", 109_000, LocalDateTime.of(2026, 6, 1, 10, 0))
        val wishId = saveWish(userId, itemId)

        mockMvc
            .perform(
                get("/api/v1/wishlists/$wishId")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.wish.id").value(wishId))
            .andExpect(jsonPath("$.data.item.id").value(itemId))
            .andExpect(jsonPath("$.data.item.status").value("READY"))
            .andExpect(jsonPath("$.data.item.method").value("STRUCTURED"))
            .andExpect(jsonPath("$.data.item.name").value("현재 상품"))
            .andExpect(jsonPath("$.data.item.price").value(109_000))
            .andExpect(jsonPath("$.data.item.sourceUrl").value("https://shop.example.com/products/history"))
            // 백오피스(source_platforms) 미등록 도메인 — host 에서 유도한 임시 표시명(등록 가능 도메인의 첫 라벨)이 나간다.
            .andExpect(jsonPath("$.data.item.sourcePlatform").value("example"))
            .andExpect(jsonPath("$.data.priceHistory.length()").value(3))
            .andExpect(jsonPath("$.data.priceHistory[0].price").value(109_000))
            // UTC 로 저장하고 응답에서 KST 로 바꾼다(JacksonConfig). KST 는 DST 가 없어 실행 환경 타임존과 무관하다
            .andExpect(jsonPath("$.data.priceHistory[0].extractedAt").value("2026-06-01T19:00:00+09:00"))
            .andExpect(jsonPath("$.data.priceHistory[0].method").value("STRUCTURED"))
            .andExpect(jsonPath("$.data.priceHistory[1].price").value(99_000))
            .andExpect(jsonPath("$.data.priceHistory[2].price").value(119_000))
            .andExpect(jsonPath("$.data.priceHistory[0].snapshotId").doesNotExist())
            .andExpect(jsonPath("$.data.priceHistory[0].isActive").doesNotExist())
            .andExpect(jsonPath("$.data.activeSnapshotId").doesNotExist())
    }

    @Test
    fun `가격 이력에는 타인의 입력도 담기고 editedByMe 로 구분된다 - 출처 미상만 빠진다`() {
        // 출처 미상(도입 전 행)은 서버 추출인지 사용자 입력인지 소급 판정할 수 없어 뺀다. 편집자 UUID 는 개인정보라 싣지 않는다
        val mockMvc = buildMockMvc()
        val me = UUID.randomUUID()
        val other = UUID.randomUUID()
        insertMember(me)
        val itemId = saveItem("https://shop.example.com/products/source-labels")
        saveVersion(itemId, "출처 미상", 90_000, method = null, userInput = false, createdBy = UUID.randomUUID())
        saveVersion(itemId, "기계 추출", 95_000, method = ExtractionMethod.STRUCTURED, userInput = false, createdBy = UUID.randomUUID())
        saveVersion(itemId, "타인 수기", 80_000, method = null, userInput = true, createdBy = other)
        saveVersion(itemId, "LLM 추출", 97_000, method = ExtractionMethod.LLM, userInput = false, createdBy = UUID.randomUUID())
        saveVersion(itemId, "내 수기", 99_000, method = null, userInput = true, createdBy = me)
        val wishId = saveWish(me, itemId)

        mockMvc
            .perform(
                get("/api/v1/wishlists/$wishId")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(me)}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.priceHistory.length()").value(4))
            .andExpect(jsonPath("$.data.priceHistory[0].price").value(99_000))
            .andExpect(jsonPath("$.data.priceHistory[0].userInput").value(true))
            .andExpect(jsonPath("$.data.priceHistory[0].editedByMe").value(true))
            .andExpect(jsonPath("$.data.priceHistory[1].price").value(97_000))
            .andExpect(jsonPath("$.data.priceHistory[1].method").value("LLM"))
            .andExpect(jsonPath("$.data.priceHistory[1].editedByMe").doesNotExist())
            .andExpect(jsonPath("$.data.priceHistory[2].price").value(80_000))
            .andExpect(jsonPath("$.data.priceHistory[2].userInput").value(true))
            .andExpect(jsonPath("$.data.priceHistory[2].editedByMe").value(false))
            .andExpect(jsonPath("$.data.priceHistory[3].price").value(95_000))
            .andExpect(jsonPath("$.data.priceHistory[3].method").value("STRUCTURED"))
            .andExpect(jsonPath("$.data.item.id").value(itemId))
            // 내 입력보다 새로운 공유 READY 가 없어 표시값은 내 입력이다(#1051)
            .andExpect(jsonPath("$.data.item.price").value(99_000))
            .andExpect(jsonPath("$.data.item.userInput").value(true))
    }

    @Test
    fun `본인이 고친 값은 표시값이 되고 이력에도 남는다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val itemId = saveItem("https://shop.example.com/products/manual-display")
        saveMachineReady(itemId, "기계 값", 109_000, LocalDateTime.of(2026, 6, 1, 10, 0))
        saveVersion(itemId, "내가 고친 값", 99_000, method = null, userInput = true, createdBy = userId)
        val wishId = saveWish(userId, itemId)

        mockMvc
            .perform(
                get("/api/v1/wishlists/$wishId")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.item.price").value(99_000))
            .andExpect(jsonPath("$.data.item.userInput").value(true))
            .andExpect(jsonPath("$.data.priceHistory.length()").value(2))
            .andExpect(jsonPath("$.data.priceHistory[0].price").value(99_000))
            .andExpect(jsonPath("$.data.priceHistory[0].userInput").value(true))
            .andExpect(jsonPath("$.data.priceHistory[0].editedByMe").value(true))
            .andExpect(jsonPath("$.data.priceHistory[1].price").value(109_000))
            .andExpect(jsonPath("$.data.priceHistory[1].method").value("STRUCTURED"))
    }

    @Test
    fun `추출이 한 번도 성공하지 못한 상품도 본인이 입력했다면 이력이 남는다`() {
        // 파싱이 계속 실패하는 몰에서는 본인 입력이 유일한 가격 기록이다
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val itemId = saveItem("https://shop.example.com/products/never-parsed")
        saveVersion(itemId, "직접 입력 1차", 120_000, method = null, userInput = true, createdBy = userId)
        saveVersion(itemId, "직접 입력 2차", 110_000, method = null, userInput = true, createdBy = userId)
        val wishId = saveWish(userId, itemId)

        mockMvc
            .perform(
                get("/api/v1/wishlists/$wishId")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.priceHistory.length()").value(2))
            .andExpect(jsonPath("$.data.priceHistory[0].price").value(110_000))
            .andExpect(jsonPath("$.data.priceHistory[0].userInput").value(true))
            .andExpect(jsonPath("$.data.priceHistory[1].price").value(120_000))
    }

    @Test
    fun `가격 이력에는 이름·가격·이미지가 다 있는 버전만 포함되고 INCOMPLETE 는 제외된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val itemId = saveItem("https://shop.example.com/products/mixed")
        saveMachineReady(itemId, "완성 버전", 50_000, LocalDateTime.now())
        itemSnapshotRepository.save(itemVersion(itemId, name = null, price = 40_000, createdBy = userId))
        itemSnapshotRepository.save(itemVersion(itemId, name = "이미지 없음", price = 45_000, imageUrl = null, createdBy = userId))
        val wishId = saveWish(userId, itemId)

        mockMvc
            .perform(
                get("/api/v1/wishlists/$wishId")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.priceHistory.length()").value(1))
            .andExpect(jsonPath("$.data.priceHistory[0].price").value(50_000))
    }

    @Test
    fun `soft-delete 된 READY snapshot 은 가격 이력에서 제외된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val itemId = saveItem("https://shop.example.com/products/soft-deleted")
        saveMachineReady(itemId, "살아있는 버전", 30_000, LocalDateTime.now())
        itemSnapshotRepository.save(
            itemVersion(itemId, name = "삭제된 버전", price = 99_000).apply { deletedAt = LocalDateTime.now() },
        )
        val wishId = saveWish(userId, itemId)

        mockMvc
            .perform(
                get("/api/v1/wishlists/$wishId")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.priceHistory.length()").value(1))
            .andExpect(jsonPath("$.data.priceHistory[0].price").value(30_000))
    }

    @Test
    fun `아이템이 확정되기 전이면 이력은 빈 배열이고 status 로 파싱 중과 실패를 가른다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val parsingUrl = "https://shop.example.com/products/parsing-only"
        val failedUrl = "https://shop.example.com/products/failed-only"
        val parsingWishId = saveUnresolvedWish(userId)
        val failedWishId = saveUnresolvedWish(userId)
        itemParseOutboxRepository.save(
            ItemParseOutbox.of(ItemSlot.wish(failedWishId), ParsePurpose.REGISTER, ParseInput.Link(ProductLink.parse(failedUrl))).apply {
                claim()
                fail()
            },
        )
        itemParseOutboxRepository.save(
            ItemParseOutbox.of(ItemSlot.wish(parsingWishId), ParsePurpose.REGISTER, ParseInput.Link(ProductLink.parse(parsingUrl))),
        )

        mockMvc
            .perform(
                get("/api/v1/wishlists/$parsingWishId")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.item.id").value(nullValue()))
            .andExpect(jsonPath("$.data.item.status").value("PARSING"))
            .andExpect(jsonPath("$.data.item.sourceUrl").value(parsingUrl))
            .andExpect(jsonPath("$.data.item.price").doesNotExist())
            .andExpect(jsonPath("$.data.priceHistory.length()").value(0))
        mockMvc
            .perform(
                get("/api/v1/wishlists/$failedWishId")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.item.id").value(nullValue()))
            .andExpect(jsonPath("$.data.item.status").value("FAILED"))
            .andExpect(jsonPath("$.data.priceHistory.length()").value(0))
    }

    @Test
    fun `가격 이력은 최신 50건까지만 내려간다`() {
        // 여러 사용자의 새로고침이 누적되므로 상한이 없으면 응답이 시간에 비례해 자란다
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val itemId = saveItem("https://shop.example.com/products/many-versions")
        repeat(55) { i ->
            saveMachineReady(itemId, "버전 $i", (i + 1) * 1_000, LocalDateTime.now())
        }
        val wishId = saveWish(userId, itemId)

        mockMvc
            .perform(
                get("/api/v1/wishlists/$wishId")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.priceHistory.length()").value(50))
            .andExpect(jsonPath("$.data.priceHistory[0].price").value(55_000))
            .andExpect(jsonPath("$.data.priceHistory[49].price").value(6_000))
    }

    @Test
    fun `옛 가격 히스토리 엔드포인트는 더 이상 존재하지 않는다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val itemId = saveItem("https://shop.example.com/products/gone")
        saveMachineReady(itemId, "상품", 10_000, LocalDateTime.now())
        val wishId = saveWish(userId, itemId)

        mockMvc
            .perform(
                get("/api/v1/wishlists/$wishId/history")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
            ).andExpect(status().isNotFound)
    }

    @Test
    fun `탈퇴한 회원의 살아있는 access token 으로 상세 조회하면 409 가 반환된다`() {
        // 본인 위시라 소유권 검증은 통과하므로 회원 가드가 활성 조회로 끊어야 한다(#691)
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertMember(userId)
        val itemId = saveItem("https://shop.example.com/products/withdrawn")
        saveMachineReady(itemId, "내 상품", 10_000, LocalDateTime.now())
        val wishId = saveWish(userId, itemId)
        jdbcTemplate.update("UPDATE users SET deleted_at = NOW(6) WHERE id = ?", uuidToBytes(userId))

        mockMvc
            .perform(
                get("/api/v1/wishlists/$wishId")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("USER-003"))
            .andExpect(jsonPath("$.detail").value("탈퇴한 계정이에요."))
    }

    private fun saveItem(url: String): Long = itemRepository.save(linkItem(url)).getId()

    // 최신순은 id 단조증가에 맡긴다
    private fun saveVersion(
        itemId: Long,
        name: String,
        price: Int,
        method: ExtractionMethod?,
        userInput: Boolean,
        createdBy: UUID,
    ): Long =
        itemSnapshotRepository
            .save(
                itemVersion(
                    itemId = itemId,
                    name = name,
                    price = price,
                    imageUrl = "https://cdn.example.com/p/$price.jpg",
                    method = method,
                    userInput = userInput,
                    createdBy = createdBy,
                ),
            ).getId()

    // created_at 은 auditing 이 채우므로 저장 뒤 SQL 로 바꾸고 영속성 컨텍스트를 비운다
    private fun saveMachineReady(
        itemId: Long,
        name: String,
        price: Int,
        createdAt: LocalDateTime,
    ): Long {
        val id =
            itemSnapshotRepository
                .save(
                    itemVersion(
                        itemId = itemId,
                        name = name,
                        price = price,
                        imageUrl = "https://cdn.example.com/p/$price.jpg",
                        method = ExtractionMethod.STRUCTURED,
                    ),
                ).getId()
        jdbcTemplate.update("UPDATE item_snapshots SET created_at = ? WHERE id = ?", createdAt, id)
        entityManager.clear()
        return id
    }

    private fun saveWish(
        userId: UUID,
        itemId: Long,
    ): Long = wishRepository.save(Wish(userId = userId, itemId = itemId)).getId()

    private fun saveUnresolvedWish(userId: UUID): Long = wishRepository.save(Wish(userId = userId, itemId = null)).getId()

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
