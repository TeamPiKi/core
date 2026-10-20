package com.depromeet.piki.wishlist.controller

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.item.domain.ItemParseOutbox
import com.depromeet.piki.item.domain.ParseInput
import com.depromeet.piki.item.domain.ParsePurpose
import com.depromeet.piki.item.domain.ItemSlot
import com.depromeet.piki.item.repository.ItemParseOutboxRepository
import com.depromeet.piki.item.repository.ItemRepository
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.item.service.ItemParsingService
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.completeLatest
import com.depromeet.piki.support.itemVersion
import com.depromeet.piki.support.linkItem
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.wishlist.domain.Wish
import com.depromeet.piki.wishlist.repository.WishRepository
import jakarta.persistence.EntityManager
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
import java.util.UUID

// 분기 망라는 ItemVersionsTest(단위)가 맡고, 여기는 목록·단건 API 가 표시값 규칙(#857·#1051)을 실제로 타는지 HTTP 경계에서 고정한다
@Transactional
class WishDisplayIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var webApplicationContext: WebApplicationContext

    @Autowired private lateinit var itemRepository: ItemRepository

    @Autowired private lateinit var itemSnapshotRepository: ItemSnapshotRepository

    @Autowired private lateinit var itemParseOutboxRepository: ItemParseOutboxRepository

    @Autowired private lateinit var wishRepository: WishRepository

    @Autowired private lateinit var itemParsingService: ItemParsingService

    @Autowired private lateinit var entityManager: EntityManager

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired private lateinit var jwtProvider: JwtProvider

    @Test
    fun `남이 만든 새 기계 READY 가 있으면 위시 목록·단건은 그 값을 보여준다`() {
        val mockMvc = buildMockMvc()
        val userA = UUID.randomUUID()
        insertMember(userA)
        val itemId = saveItem("https://shop.example.com/products/display-1")
        saveVersion(itemId, "옛 기계값", 100_000, false, by = userA)
        val wishId = saveWish(userA, itemId)
        saveVersion(itemId, "새 기계값", 90_000, false, by = UUID.randomUUID())

        mockMvc
            .perform(get("/api/v1/wishlists").header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userA)}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].item.id").value(itemId))
            .andExpect(jsonPath("$.data[0].item.name").value("새 기계값"))
            .andExpect(jsonPath("$.data[0].item.price").value(90_000))
            .andExpect(jsonPath("$.data[0].item.sourcePlatform").value("example"))
        mockMvc
            .perform(get("/api/v1/wishlists/$wishId").header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userA)}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.item.name").value("새 기계값"))
            .andExpect(jsonPath("$.data.item.price").value(90_000))
    }

    @Test
    fun `사용자 입력이 최신이면 입력한 위시만 입력값을 보고 같은 상품의 다른 위시는 기계값을 본다`() {
        val mockMvc = buildMockMvc()
        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()
        insertMember(userA)
        insertMember(userB)
        val itemId = saveItem("https://shop.example.com/products/display-2")
        saveVersion(itemId, "기계값", 100_000, false, by = userB)
        saveVersion(itemId, "A의 수기값", 80_000, true, by = userA)
        saveWish(userA, itemId)
        saveWish(userB, itemId)

        mockMvc
            .perform(get("/api/v1/wishlists").header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userA)}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].item.name").value("A의 수기값"))
            .andExpect(jsonPath("$.data[0].item.price").value(80_000))
        mockMvc
            .perform(get("/api/v1/wishlists").header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userB)}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].item.name").value("기계값"))
            .andExpect(jsonPath("$.data[0].item.price").value(100_000))
    }

    @Test
    fun `사용자 입력 뒤에 새 기계 READY 가 생기면 입력한 위시도 기계값으로 돌아간다`() {
        val mockMvc = buildMockMvc()
        val userA = UUID.randomUUID()
        insertMember(userA)
        val itemId = saveItem("https://shop.example.com/products/display-3")
        saveVersion(itemId, "기계값", 100_000, false, by = userA)
        saveVersion(itemId, "A의 수기값", 80_000, true, by = userA)
        saveWish(userA, itemId)
        saveVersion(itemId, "더 새 기계값", 95_000, false, by = UUID.randomUUID())

        mockMvc
            .perform(get("/api/v1/wishlists").header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userA)}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].item.name").value("더 새 기계값"))
            .andExpect(jsonPath("$.data[0].item.price").value(95_000))
    }

    @Test
    fun `내 새로고침이 진행 중이면 refreshing 이 켜지고 값과 상태는 새로고침 전 그대로다`() {
        val mockMvc = buildMockMvc()
        val userA = UUID.randomUUID()
        insertMember(userA)
        val url = "https://shop.example.com/products/display-5"
        val itemId = saveItem(url)
        saveVersion(itemId, "기계값", 100_000, false, by = userA)
        val wishId = saveWish(userA, itemId)
        itemParseOutboxRepository.save(ItemParseOutbox.of(ItemSlot.wish(wishId), ParsePurpose.REFRESH, ParseInput.Link(ProductLink.parse(url))))

        mockMvc
            .perform(get("/api/v1/wishlists").header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userA)}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].item.status").value("READY"))
            .andExpect(jsonPath("$.data[0].item.refreshing").value(true))
            .andExpect(jsonPath("$.data[0].item.name").value("기계값"))
            .andExpect(jsonPath("$.data[0].item.price").value(100_000))
    }

    @Test
    fun `남의 새로고침 진행과 남의 미완은 내 카드에 보이지 않는다`() {
        val mockMvc = buildMockMvc()
        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()
        insertMember(userA)
        val url = "https://shop.example.com/products/display-6"
        val itemId = saveItem(url)
        saveVersion(itemId, "기계값", 100_000, false, by = userA)
        saveWish(userA, itemId)
        val wishOfB = saveWish(userB, itemId)
        itemParseOutboxRepository.save(ItemParseOutbox.of(ItemSlot.wish(wishOfB), ParsePurpose.REFRESH, ParseInput.Link(ProductLink.parse(url))))
        saveVersion(itemId, "B의 미완", null, false, by = userB)

        mockMvc
            .perform(get("/api/v1/wishlists").header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userA)}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].item.status").value("READY"))
            .andExpect(jsonPath("$.data[0].item.refreshing").value(false))
            .andExpect(jsonPath("$.data[0].item.name").value("기계값"))
    }

    @Test
    fun `새로고침이 실패해도 내 입력값과 READY 는 그대로다`() {
        val mockMvc = buildMockMvc()
        val userA = UUID.randomUUID()
        insertMember(userA)
        val url = "https://shop.example.com/products/display-7"
        val itemId = saveItem(url)
        saveVersion(itemId, "수기 복구값", 70_000, true, by = userA)
        val wishId = saveWish(userA, itemId)
        val failedRefresh =
            ItemParseOutbox.of(ItemSlot.wish(wishId), ParsePurpose.REFRESH, ParseInput.Link(ProductLink.parse(url))).apply {
                claim()
                fail()
            }
        itemParseOutboxRepository.save(failedRefresh)

        mockMvc
            .perform(get("/api/v1/wishlists").header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userA)}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].item.status").value("READY"))
            .andExpect(jsonPath("$.data[0].item.refreshing").value(false))
            .andExpect(jsonPath("$.data[0].item.name").value("수기 복구값"))
            .andExpect(jsonPath("$.data[0].item.price").value(70_000))
    }

    @Test
    fun `내 새로고침이 INCOMPLETE 로 끝나면 옛 READY 대신 일부만 빈 상태를 보인다`() {
        val mockMvc = buildMockMvc()
        val userA = UUID.randomUUID()
        insertMember(userA)
        val itemId = saveItem("https://shop.example.com/products/display-8")
        saveVersion(itemId, "기계값", 100_000, false, by = userA)
        saveVersion(itemId, "일부만", null, false, by = userA)
        saveWish(userA, itemId)

        mockMvc
            .perform(get("/api/v1/wishlists").header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userA)}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].item.status").value("INCOMPLETE"))
            .andExpect(jsonPath("$.data[0].item.name").value("일부만"))
            .andExpect(jsonPath("$.data[0].item.price").doesNotExist())
    }

    @Test
    fun `단축 링크로 담은 위시는 파싱 전엔 등록 링크로, 성공 뒤엔 최종 상품 주소로 출처 주소와 몰 이름을 함께 바꾼다`() {
        val mockMvc = buildMockMvc()
        val userA = UUID.randomUUID()
        insertMember(userA)
        val shortUrl = "https://link.shorty.io/display-9"
        val finalUrl = "https://shop.example.com/products/display-9"
        val wish = wishRepository.save(Wish(userId = userA, itemId = null))
        itemParseOutboxRepository.save(ItemParseOutbox.of(wish.slot, ParsePurpose.REGISTER, ParseInput.Link(ProductLink.parse(shortUrl))))

        expectSource(mockMvc, userA, wish.getId(), shortUrl, "shorty")

        itemParsingService.completeLatest(
            entityManager,
            wish.slot,
            ProductSnapshot(name = "상품", price = 10_000, currency = "KRW", imageUrl = "https://cdn.example.com/p/9.jpg", finalUrl = ProductLink.parse(finalUrl)),
        )

        expectSource(mockMvc, userA, wish.getId(), finalUrl, "example")
    }

    private fun expectSource(
        mockMvc: MockMvc,
        userId: UUID,
        wishId: Long,
        sourceUrl: String,
        sourcePlatform: String,
    ) {
        mockMvc
            .perform(get("/api/v1/wishlists").header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].item.sourceUrl").value(sourceUrl))
            .andExpect(jsonPath("$.data[0].item.sourcePlatform").value(sourcePlatform))
        mockMvc
            .perform(get("/api/v1/wishlists/$wishId").header(HttpHeaders.AUTHORIZATION, "Bearer ${memberToken(userId)}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.item.sourceUrl").value(sourceUrl))
            .andExpect(jsonPath("$.data.item.sourcePlatform").value(sourcePlatform))
    }

    private fun saveItem(url: String): Long = itemRepository.save(linkItem(url)).getId()

    // 가격이 없으면 이미지도 비워 INCOMPLETE 버전이 된다
    private fun saveVersion(
        itemId: Long,
        name: String,
        price: Int?,
        userInput: Boolean,
        by: UUID,
    ): Long =
        itemSnapshotRepository
            .save(
                itemVersion(
                    itemId = itemId,
                    name = name,
                    price = price,
                    imageUrl = price?.let { "https://cdn.example.com/p/$it.jpg" },
                    userInput = userInput,
                    createdBy = by,
                ),
            ).getId()

    private fun saveWish(
        userId: UUID,
        itemId: Long,
    ): Long = wishRepository.save(Wish(userId = userId, itemId = itemId)).getId()

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
