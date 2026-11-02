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

// 위시 목록의 폴더 · 제외 폴더 · 검색어 거르기(#1265)
@Transactional
class WishlistFolderFilterIntegrationTest : IntegrationTestSupport() {
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

    // 파싱 성공과 같은 경로로 READY 위시를 만든다
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

    // 파싱 중이라 상품명이 없는 위시
    private fun pendingWish(userId: UUID): Long =
        wishPersistenceService.persist(RegisterWishFromUrlCommand(userId, ProductLink.parse("https://shop.example.com/p/${UUID.randomUUID()}"))).wish.getId()

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

    // 한 번에 하나씩 넣어 넣은 순서를 정한다
    private fun putInOrder(
        userId: UUID,
        folderId: Long,
        vararg wishIds: Long,
    ) {
        wishIds.forEach { wishId ->
            mockMvc()
                .perform(post("/api/v1/folders/$folderId/wishes").by(userId).json(mapOf("wishIds" to listOf(wishId))))
                .andExpect(status().isOk)
        }
    }

    @Test
    fun `폴더로 거르면 그 폴더 위시만 넣은 순서로 오고 cursor 로 다음 페이지를 이어 받는다`() {
        val userId = member()
        val bag = createFolder(userId, "가방")
        val a = readyWish(userId, "가")
        val b = readyWish(userId, "나")
        val c = readyWish(userId, "다")
        readyWish(userId, "폴더 밖")
        // 위시 id 순서(a, b, c)와 다르게 넣는다 — 폴더 안은 넣은 순서가 기준이다
        putInOrder(userId, bag, b, a, c)

        val firstPage =
            mockMvc()
                .perform(get("/api/v1/wishlists").param("folderId", bag.toString()).param("size", "2").by(userId))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data[*].wish.id", contains(c.toInt(), a.toInt())))
                .andExpect(jsonPath("$.pageResponse.hasNext").value(true))
                .andReturn()
                .response.contentAsString
        val cursor = objectMapper.readTree(firstPage).path("pageResponse").path("nextCursor").asString()

        mockMvc()
            .perform(get("/api/v1/wishlists").param("folderId", bag.toString()).param("size", "2").param("cursor", cursor).by(userId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[*].wish.id", contains(b.toInt())))
            .andExpect(jsonPath("$.pageResponse.hasNext").value(false))
            .andExpect(jsonPath("$.pageResponse.nextCursor").value(nullValue()))
    }

    @Test
    fun `제외 폴더로 거르면 그 폴더에 없는 위시만 오고 각 위시에 들어 있는 폴더 id 가 실린다`() {
        val userId = member()
        val bag = createFolder(userId, "가방")
        val gift = createFolder(userId, "선물")
        val inBag = readyWish(userId, "가방에 있음")
        val inGiftOnly = readyWish(userId, "선물에만 있음")
        val nowhere = readyWish(userId, "어디에도 없음")
        putInOrder(userId, bag, inBag)
        putInOrder(userId, gift, inGiftOnly, inBag)

        mockMvc()
            .perform(get("/api/v1/wishlists").param("excludeFolderId", bag.toString()).by(userId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[*].wish.id", contains(nowhere.toInt(), inGiftOnly.toInt())))
            .andExpect(jsonPath("$.data[0].wish.folderIds", hasSize<Any>(0)))
            .andExpect(jsonPath("$.data[1].wish.folderIds", contains(gift.toInt())))

        // 상세에도 같은 폴더 id 가 실린다
        mockMvc()
            .perform(get("/api/v1/wishlists/$inBag").by(userId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.wish.folderIds", contains(bag.toInt(), gift.toInt())))
    }

    @Test
    fun `검색어는 화면 이름으로 찾아 본인이 고친 이름으로 찾히고 고치기 전 이름으로는 안 찾힌다`() {
        // 화면 이름은 위시에 저장돼 있지 않고 버전에서 계산한다. 추출된 이름으로만 찾으면 고친 위시가 빠진다
        val userId = member()
        val edited = readyWish(userId, "Canvas Tote White")
        val other = readyWish(userId, "ECO 에코백 블랙")
        pendingWish(userId)
        mockMvc()
            .perform(
                multipart("/api/v1/wishlists/$edited")
                    .param("name", "내 에코백")
                    .with {
                        it.method = "PATCH"
                        it
                    }.header(HttpHeaders.AUTHORIZATION, "Bearer ${jwtProvider.generateAccessToken(userId, IdentityType.MEMBER)}"),
            ).andExpect(status().isOk)

        mockMvc()
            .perform(get("/api/v1/wishlists").param("q", " 에코 ").by(userId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[*].wish.id", contains(other.toInt(), edited.toInt())))

        mockMvc()
            .perform(get("/api/v1/wishlists").param("q", "canvas").by(userId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data", hasSize<Any>(0)))
    }

    @Test
    fun `검색어로 거른 목록도 한 페이지를 채우고 cursor 로 다음 일치 항목을 이어 받는다`() {
        val userId = member()
        val first = readyWish(userId, "에코백 1")
        readyWish(userId, "운동화")
        val second = readyWish(userId, "에코백 2")
        readyWish(userId, "모자")

        val firstPage =
            mockMvc()
                .perform(get("/api/v1/wishlists").param("q", "에코백").param("size", "1").by(userId))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data[*].wish.id", contains(second.toInt())))
                .andExpect(jsonPath("$.pageResponse.hasNext").value(true))
                .andReturn()
                .response.contentAsString
        val cursor = objectMapper.readTree(firstPage).path("pageResponse").path("nextCursor").asString()

        mockMvc()
            .perform(get("/api/v1/wishlists").param("q", "에코백").param("size", "1").param("cursor", cursor).by(userId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[*].wish.id", contains(first.toInt())))
            .andExpect(jsonPath("$.pageResponse.hasNext").value(false))
    }

    @Test
    fun `폴더에 든 위시를 지우면 폴더 목록에서 빠진다`() {
        val userId = member()
        val bag = createFolder(userId, "가방")
        val kept = readyWish(userId, "남김")
        val removed = readyWish(userId, "지움")
        putInOrder(userId, bag, kept, removed)
        mockMvc().perform(delete("/api/v1/wishlists/$removed").by(userId)).andExpect(status().isOk)

        mockMvc()
            .perform(get("/api/v1/wishlists").param("folderId", bag.toString()).by(userId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[*].wish.id", contains(kept.toInt())))
    }

    @Test
    fun `검색어가 공백뿐이면 400 WISH-011 이 반환된다`() {
        val userId = member()

        mockMvc()
            .perform(get("/api/v1/wishlists").param("q", "   ").by(userId))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value(WishErrorCode.INVALID_SEARCH_QUERY.code))
            .andExpect(jsonPath("$.detail").value(WishErrorCode.INVALID_SEARCH_QUERY.message))
    }

    @Test
    fun `남의 폴더로 거르면 404 FOLDER-001 이 반환된다`() {
        val userId = member()
        val othersFolder = createFolder(member(), "남의 폴더")

        mockMvc()
            .perform(get("/api/v1/wishlists").param("folderId", othersFolder.toString()).by(userId))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(FolderErrorCode.NOT_FOUND.code))
        mockMvc()
            .perform(get("/api/v1/wishlists").param("excludeFolderId", othersFolder.toString()).by(userId))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(FolderErrorCode.NOT_FOUND.code))
    }
}
