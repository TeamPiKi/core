package com.depromeet.piki.wishlist.controller

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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
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

// "상품 999+" · "검색 결과 N개" 의 위시 개수(#1272)
@Transactional
class WishCountIntegrationTest : IntegrationTestSupport() {
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

    private fun readyWish(
        userId: UUID,
        name: String,
    ): Long {
        val result = wishPersistenceService.persist(RegisterWishFromUrlCommand(userId, ProductLink.parse("https://shop.example.com/p/${UUID.randomUUID()}")))
        itemParsingService.completeLatest(
            entityManager,
            result.wish.slot,
            ProductSnapshot(name = name, price = 10_000, currency = "KRW", imageUrl = "https://img.example.com/a.png"),
        )
        return result.wish.getId()
    }

    private fun createFolder(
        userId: UUID,
        name: String,
    ): Long {
        val body =
            mockMvc()
                .perform(post("/api/v1/folders").by(userId).json(mapOf("name" to name)))
                .andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body).path("data").path("id").asLong()
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

    private fun count(
        userId: UUID,
        vararg params: Pair<String, String>,
    ): Long {
        val request = get("/api/v1/wishlists/count").by(userId)
        params.forEach { (name, value) -> request.param(name, value) }
        val body =
            mockMvc()
                .perform(request)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.code").value(nullValue()))
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(body).path("data").path("count").asLong()
    }

    @Test
    fun `전체 개수는 지운 위시를 빼고 센다`() {
        val userId = member()
        readyWish(userId, "가")
        val removed = readyWish(userId, "나")
        readyWish(member(), "남의 위시")
        mockMvc().perform(delete("/api/v1/wishlists/$removed").by(userId)).andExpect(status().isOk)

        assertEquals(1L, count(userId))
    }

    @Test
    fun `폴더 · 제외 폴더 · 둘 다로 센 개수가 목록과 같은 규칙이다`() {
        val userId = member()
        val bag = createFolder(userId, "가방")
        val gift = createFolder(userId, "선물")
        val inBoth = readyWish(userId, "둘 다")
        val bagOnly = readyWish(userId, "가방만")
        readyWish(userId, "어디에도 없음")
        put(userId, bag, inBoth, bagOnly)
        put(userId, gift, inBoth)

        assertEquals(2L, count(userId, "folderId" to bag.toString()))
        assertEquals(2L, count(userId, "excludeFolderId" to gift.toString()))
        assertEquals(1L, count(userId, "folderId" to bag.toString(), "excludeFolderId" to gift.toString()))
    }

    @Test
    fun `검색 개수는 화면 이름(본인이 고친 이름 포함)으로 센다`() {
        val userId = member()
        val edited = readyWish(userId, "Canvas Tote")
        readyWish(userId, "에코백 블랙")
        readyWish(userId, "운동화")
        mockMvc()
            .perform(
                multipart("/api/v1/wishlists/$edited")
                    .param("name", "내 에코백")
                    .with {
                        it.method = "PATCH"
                        it
                    }.header(HttpHeaders.AUTHORIZATION, "Bearer ${jwtProvider.generateAccessToken(userId, IdentityType.MEMBER)}"),
            ).andExpect(status().isOk)

        assertEquals(2L, count(userId, "q" to "에코"))
        assertEquals(0L, count(userId, "q" to "canvas"))
    }

    @Test
    fun `검색어가 공백뿐이면 400 WISH-011 이고 남의 폴더면 404 FOLDER-001 이다`() {
        val userId = member()
        val othersFolder = createFolder(member(), "남의 폴더")

        mockMvc()
            .perform(get("/api/v1/wishlists/count").param("q", "  ").by(userId))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value(WishErrorCode.INVALID_SEARCH_QUERY.code))
        mockMvc()
            .perform(get("/api/v1/wishlists/count").param("folderId", othersFolder.toString()).by(userId))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(FolderErrorCode.NOT_FOUND.code))
        mockMvc()
            .perform(get("/api/v1/wishlists/count").param("excludeFolderId", othersFolder.toString()).by(userId))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(FolderErrorCode.NOT_FOUND.code))
    }
}
