package com.depromeet.piki.explore.service

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.explore.repository.ExploreFeedStore
import com.depromeet.piki.item.service.ItemParsingService
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.completeLatest
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.wishlist.service.WishPersistenceService
import com.depromeet.piki.wishlist.service.dto.RegisterWishFromUrlCommand
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// 다른 테스트가 커밋한 위시도 같은 피드에 섞이므로, 목록 전체가 아니라 이 테스트가 만든 위시의 유무만 본다
@Transactional
class ExploreFeedRefreshIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var webApplicationContext: WebApplicationContext

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired private lateinit var jwtProvider: JwtProvider

    @Autowired private lateinit var entityManager: EntityManager

    @Autowired private lateinit var wishPersistenceService: WishPersistenceService

    @Autowired private lateinit var itemParsingService: ItemParsingService

    @Autowired private lateinit var exploreFeedRefresher: ExploreFeedRefresher

    @Autowired private lateinit var exploreFeedStore: ExploreFeedStore

    @Test
    fun `프로필이 공개인 사람의 위시는 폴더에 들었든 아니든 피드에 오른다`() {
        val owner = member()
        val loose = readyWish(owner)
        val inFolder = readyWish(owner)
        putWish(owner, createFolder(owner, "폴더"), inFolder)

        val wishIds = refreshedWishIds()

        assertTrue(loose in wishIds)
        assertTrue(inFolder in wishIds)
    }

    @Test
    fun `프로필을 비공개로 둔 사람의 위시는 폴더와 관계없이 빠진다`() {
        val owner = member()
        val loose = readyWish(owner)
        val inFolder = readyWish(owner)
        putWish(owner, createFolder(owner, "폴더"), inFolder)
        makeProfilePrivate(owner)

        val wishIds = refreshedWishIds()

        assertFalse(loose in wishIds)
        assertFalse(inFolder in wishIds)
    }

    @Test
    fun `지운 위시와 상품이 아직 정해지지 않은 위시는 빠진다`() {
        val owner = member()
        val deleted = readyWish(owner)
        deleteWish(owner, deleted)
        val parsing = parsingWish(owner)

        val wishIds = refreshedWishIds()

        assertFalse(deleted in wishIds)
        assertFalse(parsing in wishIds)
    }

    @Test
    fun `카드에 담은 사람과 상품의 화면값이 실린다`() {
        val owner = member()
        val wishId = readyWish(owner)

        exploreFeedRefresher.refresh()

        val card = assertNotNull(exploreFeedStore.find()).cards.single { it.wishId == wishId }
        assertEquals(owner, card.ownerId)
        assertEquals("상품", card.name)
        assertEquals(10_000, card.price)
        assertEquals("https://img.example.com/a.png", card.imageUrl)
        assertNotNull(card.sourceUrl)
    }

    private fun refreshedWishIds(): Set<Long> {
        exploreFeedRefresher.refresh()
        return assertNotNull(exploreFeedStore.find()).cards.map { it.wishId }.toSet()
    }

    private fun mockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    private fun member(): UUID =
        UUID.randomUUID().also { userId ->
            jdbcTemplate.update(
                "INSERT INTO users (id, nickname, identity_type, created_at, updated_at) VALUES (?, ?, ?, NOW(6), NOW(6))",
                uuidToBytes(userId),
                userId.toString().take(10),
                IdentityType.MEMBER.name,
            )
        }

    private fun MockHttpServletRequestBuilder.by(userId: UUID): MockHttpServletRequestBuilder =
        header(HttpHeaders.AUTHORIZATION, "Bearer ${jwtProvider.generateAccessToken(userId, IdentityType.MEMBER)}")

    private fun MockHttpServletRequestBuilder.json(body: Any): MockHttpServletRequestBuilder =
        contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body))

    private fun parsingWish(userId: UUID): Long =
        wishPersistenceService
            .persist(
                RegisterWishFromUrlCommand(userId, ProductLink.parse("https://shop.example.com/p/${UUID.randomUUID()}")),
            ).wish
            .getId()

    private fun readyWish(userId: UUID): Long {
        val result =
            wishPersistenceService.persist(
                RegisterWishFromUrlCommand(userId, ProductLink.parse("https://shop.example.com/p/${UUID.randomUUID()}")),
            )
        itemParsingService.completeLatest(
            entityManager,
            result.wish.slot,
            ProductSnapshot(name = "상품", price = 10_000, currency = "KRW", imageUrl = "https://img.example.com/a.png"),
        )
        return result.wish.getId()
    }

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
        return objectMapper
            .readTree(response)
            .path("data")
            .path("id")
            .asLong()
    }

    private fun putWish(
        userId: UUID,
        folderId: Long,
        wishId: Long,
    ) {
        mockMvc()
            .perform(post("/api/v1/folders/$folderId/wishes").by(userId).json(mapOf("wishIds" to listOf(wishId))))
            .andExpect(status().isOk)
    }

    private fun makeProfilePrivate(userId: UUID) {
        mockMvc()
            .perform(patch("/api/v1/users/me").by(userId).json(mapOf("isProfilePublic" to false)))
            .andExpect(status().isOk)
    }

    private fun deleteWish(
        userId: UUID,
        wishId: Long,
    ) {
        mockMvc()
            .perform(delete("/api/v1/wishlists/$wishId").by(userId))
            .andExpect(status().isOk)
    }
}
