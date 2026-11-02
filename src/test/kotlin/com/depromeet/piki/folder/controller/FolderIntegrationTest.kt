package com.depromeet.piki.folder.controller

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.folder.domain.FolderErrorCode
import com.depromeet.piki.item.service.ItemParsingService
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.completeLatest
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.wishlist.domain.WishErrorCode
import com.depromeet.piki.wishlist.service.WishPersistenceService
import com.depromeet.piki.wishlist.service.dto.RegisterWishFromUrlCommand
import jakarta.persistence.EntityManager
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.test.assertEquals

@Transactional
class FolderIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var entityManager: EntityManager

    @Autowired private lateinit var webApplicationContext: WebApplicationContext

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var wishPersistenceService: WishPersistenceService

    @Autowired private lateinit var itemParsingService: ItemParsingService

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired private lateinit var jwtProvider: JwtProvider

    private fun mockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    private fun insertUser(
        userId: UUID,
        identityType: IdentityType,
    ) {
        jdbcTemplate.update(
            "INSERT INTO users (id, nickname, identity_type, created_at, updated_at) VALUES (?, ?, ?, NOW(6), NOW(6))",
            uuidToBytes(userId),
            userId.toString().take(10),
            identityType.name,
        )
    }

    private fun member(): UUID = UUID.randomUUID().also { insertUser(it, IdentityType.MEMBER) }

    private fun MockHttpServletRequestBuilder.by(
        userId: UUID,
        identityType: IdentityType = IdentityType.MEMBER,
    ): MockHttpServletRequestBuilder = header(HttpHeaders.AUTHORIZATION, "Bearer ${jwtProvider.generateAccessToken(userId, identityType)}")

    private fun MockHttpServletRequestBuilder.json(body: Any): MockHttpServletRequestBuilder =
        contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body))

    // 파싱 성공과 같은 경로로 READY 위시를 만든다. imageUrl 이 표지 후보가 된다
    private fun readyWish(
        userId: UUID,
        imageUrl: String,
    ): Long {
        val result = wishPersistenceService.persist(RegisterWishFromUrlCommand(userId, ProductLink.parse("https://shop.example.com/p/${UUID.randomUUID()}")))
        itemParsingService.completeLatest(entityManager, result.wish.slot, ProductSnapshot(name = "상품", price = 10_000, currency = "KRW", imageUrl = imageUrl))
        return result.wish.getId()
    }

    // 파싱 중이라 이미지가 없는 위시
    private fun pendingWish(userId: UUID): Long =
        wishPersistenceService.persist(RegisterWishFromUrlCommand(userId, ProductLink.parse("https://shop.example.com/p/${UUID.randomUUID()}"))).wish.getId()

    private fun createFolder(
        userId: UUID,
        name: String,
    ): Long {
        val response =
            mockMvc()
                .perform(post("/api/v1/folders").by(userId).json(mapOf("name" to name)))
                .andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(response).path("data").path("id").asLong()
    }

    private fun put(
        userId: UUID,
        folderId: Long,
        vararg wishIds: Long,
    ) {
        mockMvc()
            .perform(post("/api/v1/folders/$folderId/wishes").by(userId).json(mapOf("wishIds" to wishIds.toList())))
            .andExpect(status().isOk)
    }

    @Test
    fun `폴더를 만들면 201 과 앞뒤 공백을 지운 이름의 빈 폴더가 반환된다`() {
        val userId = member()

        mockMvc()
            .perform(post("/api/v1/folders").by(userId).json(mapOf("name" to "  겨울 아우터 ")))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.code").value(nullValue()))
            .andExpect(jsonPath("$.data.name").value("겨울 아우터"))
            .andExpect(jsonPath("$.data.wishCount").value(0))
            .andExpect(jsonPath("$.data.coverImageUrls", hasSize<Any>(0)))
    }

    @Test
    fun `같은 이름의 폴더를 두 번 만들 수 있다`() {
        val userId = member()
        createFolder(userId, "선물")
        createFolder(userId, "선물")

        mockMvc()
            .perform(get("/api/v1/folders").by(userId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[*].name", contains("선물", "선물")))
    }

    @Test
    fun `21자 이름으로 만들면 400 FOLDER-002 가 반환된다`() {
        val userId = member()

        mockMvc()
            .perform(post("/api/v1/folders").by(userId).json(mapOf("name" to "가".repeat(21))))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value(FolderErrorCode.INVALID_NAME.code))
            .andExpect(jsonPath("$.detail").value(FolderErrorCode.INVALID_NAME.message))
    }

    @Test
    fun `게스트가 폴더를 만들면 403 WISH-001 이 반환된다`() {
        val userId = UUID.randomUUID().also { insertUser(it, IdentityType.GUEST) }

        mockMvc()
            .perform(post("/api/v1/folders").by(userId, IdentityType.GUEST).json(mapOf("name" to "가방")))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value(WishErrorCode.GUEST_CANNOT_USE_WISHLIST.code))
    }

    @Test
    fun `폴더 목록은 내 폴더만 생성순으로 주고 표지는 최근 넣은 위시부터 이미지 있는 것만 3장이다`() {
        val userId = member()
        val other = member()
        createFolder(other, "남의 폴더")
        val first = createFolder(userId, "가방")
        createFolder(userId, "신발")
        val w1 = readyWish(userId, "https://img.example.com/1.png")
        val w2 = readyWish(userId, "https://img.example.com/2.png")
        val w3 = readyWish(userId, "https://img.example.com/3.png")
        val w4 = readyWish(userId, "https://img.example.com/4.png")
        val w5 = pendingWish(userId)
        listOf(w1, w2, w3, w4, w5).forEach { put(userId, first, it) }

        mockMvc()
            .perform(get("/api/v1/folders").by(userId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[*].name", contains("가방", "신발")))
            .andExpect(jsonPath("$.data[0].wishCount").value(5))
            .andExpect(
                jsonPath(
                    "$.data[0].coverImageUrls",
                    contains("https://img.example.com/4.png", "https://img.example.com/3.png", "https://img.example.com/2.png"),
                ),
            ).andExpect(jsonPath("$.data[1].wishCount").value(0))
    }

    @Test
    fun `지운 위시는 폴더 개수와 표지에서 빠진다`() {
        val userId = member()
        val folderId = createFolder(userId, "가방")
        val kept = readyWish(userId, "https://img.example.com/kept.png")
        val removed = readyWish(userId, "https://img.example.com/removed.png")
        put(userId, folderId, kept, removed)

        mockMvc().perform(delete("/api/v1/wishlists/$removed").by(userId)).andExpect(status().isOk)

        mockMvc()
            .perform(get("/api/v1/folders").by(userId))
            .andExpect(jsonPath("$.data[0].wishCount").value(1))
            .andExpect(jsonPath("$.data[0].coverImageUrls", contains("https://img.example.com/kept.png")))
    }

    @Test
    fun `남의 폴더 이름을 바꾸면 404 FOLDER-001 이 반환된다`() {
        val owner = member()
        val folderId = createFolder(owner, "가방")

        mockMvc()
            .perform(patch("/api/v1/folders/$folderId").by(member()).json(mapOf("name" to "내꺼")))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(FolderErrorCode.NOT_FOUND.code))
    }

    @Test
    fun `폴더 이름을 바꾸면 200 과 바뀐 이름이 반환된다`() {
        val userId = member()
        val folderId = createFolder(userId, "가방")

        mockMvc()
            .perform(patch("/api/v1/folders/$folderId").by(userId).json(mapOf("name" to "코트")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.id").value(folderId))
            .andExpect(jsonPath("$.data.name").value("코트"))
    }

    @Test
    fun `폴더를 지우면 목록에서 빠지고 안의 위시는 남는다`() {
        val userId = member()
        val folderId = createFolder(userId, "가방")
        val wishId = readyWish(userId, "https://img.example.com/a.png")
        put(userId, folderId, wishId)

        mockMvc()
            .perform(delete("/api/v1/folders/$folderId").by(userId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").value(nullValue()))

        mockMvc().perform(get("/api/v1/folders").by(userId)).andExpect(jsonPath("$.data", hasSize<Any>(0)))
        mockMvc().perform(get("/api/v1/wishlists/$wishId").by(userId)).andExpect(status().isOk)
        entityManager.flush()
        assertEquals(0L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM folder_wishes WHERE folder_id = ?", Long::class.java, folderId))
    }

    @Test
    fun `이미 들어 있는 위시를 다시 넣으면 건너뛰고 개수만 돌려준다`() {
        val userId = member()
        val folderId = createFolder(userId, "가방")
        val a = readyWish(userId, "https://img.example.com/a.png")
        val b = readyWish(userId, "https://img.example.com/b.png")
        put(userId, folderId, a)

        mockMvc()
            .perform(post("/api/v1/folders/$folderId/wishes").by(userId).json(mapOf("wishIds" to listOf(a, b))))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.folderId").value(folderId))
            .andExpect(jsonPath("$.data.wishCount").value(2))
    }

    @Test
    fun `남의 위시가 섞이면 403 WISH-002 로 전체를 거절하고 아무것도 넣지 않는다`() {
        val userId = member()
        val folderId = createFolder(userId, "가방")
        val mine = readyWish(userId, "https://img.example.com/a.png")
        val others = readyWish(member(), "https://img.example.com/b.png")

        mockMvc()
            .perform(post("/api/v1/folders/$folderId/wishes").by(userId).json(mapOf("wishIds" to listOf(mine, others))))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value(WishErrorCode.FORBIDDEN_WISH_ITEMS.code))

        mockMvc().perform(get("/api/v1/folders").by(userId)).andExpect(jsonPath("$.data[0].wishCount").value(0))
    }

    @Test
    fun `지운 위시가 섞여도 403 WISH-002 로 거절한다`() {
        val userId = member()
        val folderId = createFolder(userId, "가방")
        val deleted = readyWish(userId, "https://img.example.com/a.png")
        mockMvc().perform(delete("/api/v1/wishlists/$deleted").by(userId)).andExpect(status().isOk)

        mockMvc()
            .perform(post("/api/v1/folders/$folderId/wishes").by(userId).json(mapOf("wishIds" to listOf(deleted))))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value(WishErrorCode.FORBIDDEN_WISH_ITEMS.code))
    }

    @Test
    fun `폴더에서 빼면 그 폴더에서만 빠지고 다른 폴더와 위시는 남는다`() {
        val userId = member()
        val bag = createFolder(userId, "가방")
        val gift = createFolder(userId, "선물")
        val wishId = readyWish(userId, "https://img.example.com/a.png")
        put(userId, bag, wishId)
        put(userId, gift, wishId)

        mockMvc()
            .perform(delete("/api/v1/folders/$bag/wishes").param("wishIds", wishId.toString(), "999999").by(userId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.wishCount").value(0))

        mockMvc().perform(get("/api/v1/folders").by(userId)).andExpect(jsonPath("$.data[1].wishCount").value(1))
        mockMvc().perform(get("/api/v1/wishlists/$wishId").by(userId)).andExpect(status().isOk)
    }

    @Test
    fun `wishIds 없이 빼면 400 FOLDER-003 이 반환된다`() {
        val userId = member()
        val folderId = createFolder(userId, "가방")

        mockMvc()
            .perform(delete("/api/v1/folders/$folderId/wishes").by(userId))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value(FolderErrorCode.INVALID_WISH_COUNT.code))
    }

    @Test
    fun `옮기면 원래 폴더에서 빠지고 대상에 이미 있던 위시는 중복 없이 남는다`() {
        val userId = member()
        val bag = createFolder(userId, "가방")
        val gift = createFolder(userId, "선물")
        val a = readyWish(userId, "https://img.example.com/a.png")
        val b = readyWish(userId, "https://img.example.com/b.png")
        put(userId, bag, a, b)
        put(userId, gift, b)

        mockMvc()
            .perform(post("/api/v1/folders/$bag/wishes/move").by(userId).json(mapOf("wishIds" to listOf(a, b), "targetFolderId" to gift)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.folderId").value(bag))
            .andExpect(jsonPath("$.data.wishCount").value(0))
            .andExpect(jsonPath("$.data.targetFolderId").value(gift))
            .andExpect(jsonPath("$.data.targetWishCount").value(2))
    }

    @Test
    fun `같은 폴더로 옮기면 400 FOLDER-004 가 반환된다`() {
        val userId = member()
        val bag = createFolder(userId, "가방")
        val a = readyWish(userId, "https://img.example.com/a.png")
        put(userId, bag, a)

        mockMvc()
            .perform(post("/api/v1/folders/$bag/wishes/move").by(userId).json(mapOf("wishIds" to listOf(a), "targetFolderId" to bag)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value(FolderErrorCode.SAME_FOLDER_MOVE.code))
    }

    @Test
    fun `폴더에 남은 지운 위시를 옮기면 403 WISH-002 로 거절하고 대상 폴더에 연결이 생기지 않는다`() {
        val userId = member()
        val bag = createFolder(userId, "가방")
        val gift = createFolder(userId, "선물")
        val deleted = readyWish(userId, "https://img.example.com/a.png")
        put(userId, bag, deleted)
        mockMvc().perform(delete("/api/v1/wishlists/$deleted").by(userId)).andExpect(status().isOk)

        mockMvc()
            .perform(post("/api/v1/folders/$bag/wishes/move").by(userId).json(mapOf("wishIds" to listOf(deleted), "targetFolderId" to gift)))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value(WishErrorCode.FORBIDDEN_WISH_ITEMS.code))

        entityManager.flush()
        assertEquals(0L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM folder_wishes WHERE folder_id = ?", Long::class.java, gift))
        // 거절 전에 원래 폴더의 연결을 지우는 순서 회귀를 잡는다
        assertEquals(1L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM folder_wishes WHERE folder_id = ? AND wish_id = ?", Long::class.java, bag, deleted))
    }

    @Test
    fun `남의 폴더로 옮기면 404 FOLDER-001 이 반환되고 원래 폴더는 그대로다`() {
        val userId = member()
        val bag = createFolder(userId, "가방")
        val othersFolder = createFolder(member(), "남의 폴더")
        val a = readyWish(userId, "https://img.example.com/a.png")
        put(userId, bag, a)

        mockMvc()
            .perform(post("/api/v1/folders/$bag/wishes/move").by(userId).json(mapOf("wishIds" to listOf(a), "targetFolderId" to othersFolder)))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(FolderErrorCode.NOT_FOUND.code))

        mockMvc().perform(get("/api/v1/folders").by(userId)).andExpect(jsonPath("$.data[0].wishCount").value(1))
    }
}
