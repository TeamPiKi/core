package com.depromeet.piki.folder.controller

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.folder.domain.FolderErrorCode
import com.depromeet.piki.item.domain.ItemSlot
import com.depromeet.piki.item.service.ItemParsingService
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.completeLatest
import com.depromeet.piki.support.presignImages
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.wishlist.domain.WishErrorCode
import com.depromeet.piki.wishlist.domain.WishFolderResult
import com.depromeet.piki.wishlist.service.WishPersistenceService
import com.depromeet.piki.wishlist.service.dto.RegisterWishFromUrlCommand
import jakarta.persistence.EntityManager
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
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

// 위시를 담으며 폴더에 넣기, 이미 담은 링크를 폴더에 넣기, 위시가 합쳐질 때 폴더 연결 옮기기(#1266)
@Transactional
class FolderWishRegisterIntegrationTest : IntegrationTestSupport() {
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

    private fun member(): UUID =
        UUID.randomUUID().also {
            jdbcTemplate.update(
                "INSERT INTO users (id, nickname, identity_type, created_at, updated_at) VALUES (?, ?, 'MEMBER', NOW(6), NOW(6))",
                uuidToBytes(it),
                it.toString().take(10),
            )
        }

    private fun MockHttpServletRequestBuilder.by(userId: UUID): MockHttpServletRequestBuilder =
        header(HttpHeaders.AUTHORIZATION, "Bearer ${jwtProvider.generateAccessToken(userId, IdentityType.MEMBER)}")

    private fun MockHttpServletRequestBuilder.json(body: Any): MockHttpServletRequestBuilder =
        contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body))

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

    private fun wishIdsIn(folderId: Long): List<Long> {
        entityManager.flush()
        return jdbcTemplate.queryForList("SELECT wish_id FROM folder_wishes WHERE folder_id = ? ORDER BY wish_id", Long::class.java, folderId).filterNotNull()
    }

    private fun wishCountOf(userId: UUID): Int {
        entityManager.flush()
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wishes WHERE user_id = ? AND deleted_at IS NULL", Int::class.java, uuidToBytes(userId)) ?: 0
    }

    private fun complete(
        slot: ItemSlot,
        finalUrl: String,
    ) {
        itemParsingService.completeLatest(
            entityManager,
            slot,
            ProductSnapshot(
                name = "공유 상품",
                price = 10_000,
                currency = "KRW",
                imageUrl = "https://img.example.com/s.png",
                finalUrl = ProductLink.parse(finalUrl),
            ),
        )
    }

    @Test
    fun `폴더를 지정해 링크를 담으면 201 이고 새 위시가 그 폴더에 들어간다`() {
        val userId = member()
        val folderId = createFolder(userId, "가방")

        val response =
            mockMvc()
                .perform(post("/api/v1/wishlists").by(userId).json(mapOf("url" to "https://www.musinsa.com/products/9300001", "folderId" to folderId)))
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.data.item.status").value("PARSING"))
                .andExpect(jsonPath("$.data.folderResult").value(nullValue()))
                .andReturn()
                .response.contentAsString

        val wishId = objectMapper.readTree(response).path("data").path("wish").path("id").asLong()
        assertEquals(listOf(wishId), wishIdsIn(folderId))
    }

    @Test
    fun `폴더를 지정해 이미 담은 링크를 다시 담으면 409 대신 기존 위시를 그 폴더에 넣고 ADDED 를 준다`() {
        val userId = member()
        val folderId = createFolder(userId, "가방")
        val existing = wishPersistenceService.persist(RegisterWishFromUrlCommand(userId, ProductLink.parse("https://www.musinsa.com/products/9300002"))).wish

        mockMvc()
            .perform(post("/api/v1/wishlists").by(userId).json(mapOf("url" to "https://www.musinsa.com/products/9300002", "folderId" to folderId)))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.code").value(nullValue()))
            .andExpect(jsonPath("$.data.wish.id").value(existing.getId()))
            .andExpect(jsonPath("$.data.folderResult").value(WishFolderResult.ADDED.name))

        assertEquals(listOf(existing.getId()), wishIdsIn(folderId))
        assertEquals(1, wishCountOf(userId), "새 위시는 생기지 않는다")
    }

    @Test
    fun `이미 그 폴더에 있는 위시의 링크를 다시 담으면 ALREADY_IN_FOLDER 이고 연결은 하나다`() {
        val userId = member()
        val folderId = createFolder(userId, "가방")
        val existing =
            wishPersistenceService
                .persist(RegisterWishFromUrlCommand(userId, ProductLink.parse("https://www.musinsa.com/products/9300003"), folderId = folderId))
                .wish

        mockMvc()
            .perform(post("/api/v1/wishlists").by(userId).json(mapOf("url" to "https://www.musinsa.com/products/9300003", "folderId" to folderId)))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.wish.id").value(existing.getId()))
            .andExpect(jsonPath("$.data.folderResult").value(WishFolderResult.ALREADY_IN_FOLDER.name))

        assertEquals(listOf(existing.getId()), wishIdsIn(folderId))
    }

    @Test
    fun `폴더 없이 이미 담은 링크를 다시 담으면 지금처럼 409 WISH-009 다`() {
        val userId = member()
        wishPersistenceService.persist(RegisterWishFromUrlCommand(userId, ProductLink.parse("https://www.musinsa.com/products/9300004")))

        mockMvc()
            .perform(post("/api/v1/wishlists").by(userId).json(mapOf("url" to "https://www.musinsa.com/products/9300004")))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value(WishErrorCode.ALREADY_EXISTS.code))
    }

    @Test
    fun `남의 폴더를 지정해 담으면 404 FOLDER-001 이고 위시가 생기지 않는다`() {
        val userId = member()
        val othersFolder = createFolder(member(), "남의 폴더")

        mockMvc()
            .perform(post("/api/v1/wishlists").by(userId).json(mapOf("url" to "https://www.musinsa.com/products/9300005", "folderId" to othersFolder)))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(FolderErrorCode.NOT_FOUND.code))

        assertEquals(0, wishCountOf(userId))
        assertEquals(emptyList(), wishIdsIn(othersFolder))
    }

    @Test
    fun `폴더를 지정해 이미지를 확정하면 새로 담긴 위시가 모두 그 폴더에 들어간다`() {
        val userId = member()
        val folderId = createFolder(userId, "가방")
        val presigned =
            mockMvc()
                .perform(post("/api/v1/wishlists/images/presigned").by(userId).json(presignImages(listOf("image/png", "image/jpeg"))))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val uploads = objectMapper.readTree(presigned).path("data").path("uploads")
        val keys = (0 until uploads.size()).map { uploads.get(it).path("imageKey").asString() }

        val response =
            mockMvc()
                .perform(post("/api/v1/wishlists/images/confirm").by(userId).json(mapOf("imageKeys" to keys, "folderId" to folderId)))
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.data.length()").value(2))
                .andReturn()
                .response.contentAsString

        val data = objectMapper.readTree(response).path("data")
        val wishIds = (0 until data.size()).map { data.get(it).path("wish").path("id").asLong() }.sorted()
        assertEquals(wishIds, wishIdsIn(folderId))
    }

    @Test
    fun `폴더에 든 위시가 파싱 뒤 같은 상품의 앞 위시에 합쳐지면 폴더 연결이 앞 위시로 옮겨진다`() {
        val userId = member()
        val folderId = createFolder(userId, "가방")
        val finalUrl = "https://www.musinsa.com/products/9300006"
        val first = wishPersistenceService.persist(RegisterWishFromUrlCommand(userId, ProductLink.parse("https://musinsa.onelink.me/PvkC/fold0006"))).wish
        val second = wishPersistenceService.persist(RegisterWishFromUrlCommand(userId, ProductLink.parse(finalUrl), folderId = folderId)).wish
        complete(first.slot, finalUrl)

        complete(second.slot, finalUrl)

        // 옮기지 않으면 폴더가 지워진 second 를 가리켜 사용자가 넣은 상품이 폴더에서 빠져 보인다
        assertEquals(listOf(first.getId()), wishIdsIn(folderId))
    }

    @Test
    fun `남는 위시가 이미 같은 폴더에 있으면 합쳐진 뒤 연결은 하나만 남는다`() {
        val userId = member()
        val folderId = createFolder(userId, "가방")
        val finalUrl = "https://www.musinsa.com/products/9300007"
        val first = wishPersistenceService.persist(RegisterWishFromUrlCommand(userId, ProductLink.parse("https://musinsa.onelink.me/PvkC/fold0007"), folderId = folderId)).wish
        val second = wishPersistenceService.persist(RegisterWishFromUrlCommand(userId, ProductLink.parse(finalUrl), folderId = folderId)).wish
        complete(first.slot, finalUrl)

        complete(second.slot, finalUrl)

        assertEquals(listOf(first.getId()), wishIdsIn(folderId))
    }
}
