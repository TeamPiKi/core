package com.depromeet.piki.explore.controller

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
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
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
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
import kotlin.test.assertNotEquals

@Transactional
class ExploreCopyAndFriendsIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var webApplicationContext: WebApplicationContext

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired private lateinit var jwtProvider: JwtProvider

    @Autowired private lateinit var entityManager: EntityManager

    @Autowired private lateinit var wishPersistenceService: WishPersistenceService

    @Autowired private lateinit var itemParsingService: ItemParsingService

    @Test
    fun `다른 사람의 위시를 담으면 같은 상품의 내 위시가 생기고 내 위시 목록에 나온다`() {
        val mockMvc = mockMvc()
        val owner = user(IdentityType.MEMBER)
        val viewer = user(IdentityType.MEMBER)
        val source = readyWish(owner)

        val copied = copy(mockMvc, viewer, source)

        assertNotEquals(source, copied.path("wishId").asLong())
        assertEquals(false, copied.path("alreadySaved").asBoolean())
        mockMvc
            .perform(get("/api/v1/wishlists").by(viewer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data", hasSize<Any>(1)))
            .andExpect(jsonPath("$.data[0].wish.id").value(copied.path("wishId").asLong()))
            .andExpect(jsonPath("$.data[0].item.name").value("상품"))
        mockMvc
            .perform(get("/api/v1/explore/wishes/$source").by(viewer))
            .andExpect(jsonPath("$.data.saved").value(true))
            .andExpect(jsonPath("$.data.savedUserCount").value(2))
    }

    @Test
    fun `이미 가진 상품을 다시 담으면 새로 만들지 않고 원래 위시를 돌려준다`() {
        val mockMvc = mockMvc()
        val owner = user(IdentityType.MEMBER)
        val viewer = user(IdentityType.MEMBER)
        val source = readyWish(owner)
        val first = copy(mockMvc, viewer, source)

        val second = copy(mockMvc, viewer, source)

        assertEquals(first.path("wishId").asLong(), second.path("wishId").asLong())
        assertEquals(true, second.path("alreadySaved").asBoolean())
    }

    @Test
    fun `비로그인은 401 이고 게스트는 403 이고 없는 위시는 404 다`() {
        val mockMvc = mockMvc()
        val owner = user(IdentityType.MEMBER)
        val source = readyWish(owner)

        mockMvc
            .perform(post("/api/v1/explore/wishes/$source/copy"))
            .andExpect(status().isUnauthorized)
        mockMvc
            .perform(post("/api/v1/explore/wishes/$source/copy").by(user(IdentityType.GUEST), IdentityType.GUEST))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value(WishErrorCode.GUEST_CANNOT_USE_WISHLIST.code))
        mockMvc
            .perform(post("/api/v1/explore/wishes/${Long.MAX_VALUE}/copy").by(user(IdentityType.MEMBER)))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(WishErrorCode.NOT_FOUND.code))
    }

    @Test
    fun `친구가 담은 위시가 최신순으로 나오고 친구가 아닌 사람의 위시는 빠진다`() {
        val mockMvc = mockMvc()
        val viewer = user(IdentityType.MEMBER)
        val quiet = user(IdentityType.MEMBER)
        val active = user(IdentityType.MEMBER)
        val stranger = user(IdentityType.MEMBER)
        befriend(mockMvc, viewer, quiet)
        befriend(mockMvc, viewer, active)
        val older = readyWish(active)
        readyWish(stranger)
        val newer = readyWish(active)

        mockMvc
            .perform(get("/api/v1/explore/friends").by(viewer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.wishes", hasSize<Any>(2)))
            .andExpect(jsonPath("$.data.wishes[0].wishId").value(newer))
            .andExpect(jsonPath("$.data.wishes[1].wishId").value(older))
            .andExpect(jsonPath("$.data.wishes[0].friend.userId").value(active.toString()))
            .andExpect(jsonPath("$.data.wishes[0].item.name").value("상품"))
            .andExpect(jsonPath("$.data.wishes[0].saved").value(false))
            // 칩은 최근에 담은 친구가 앞이고, 위시가 없는 친구도 들어 있다
            .andExpect(jsonPath("$.data.friends", hasSize<Any>(2)))
            .andExpect(jsonPath("$.data.friends[0].userId").value(active.toString()))
            .andExpect(jsonPath("$.data.friends[1].userId").value(quiet.toString()))
            .andExpect(jsonPath("$.pageResponse.hasNext").value(false))
    }

    @Test
    fun `친구를 고르면 그 친구의 위시만 나오고 크기만큼 끊어 다음 쪽으로 이어진다`() {
        val mockMvc = mockMvc()
        val viewer = user(IdentityType.MEMBER)
        val chosen = user(IdentityType.MEMBER)
        val other = user(IdentityType.MEMBER)
        befriend(mockMvc, viewer, chosen)
        befriend(mockMvc, viewer, other)
        val first = readyWish(chosen)
        readyWish(other)
        val second = readyWish(chosen)

        mockMvc
            .perform(get("/api/v1/explore/friends").by(viewer).param("friendId", chosen.toString()).param("size", "1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.wishes", hasSize<Any>(1)))
            .andExpect(jsonPath("$.data.wishes[0].wishId").value(second))
            .andExpect(jsonPath("$.pageResponse.hasNext").value(true))
            .andExpect(jsonPath("$.pageResponse.nextCursor").value(second.toString()))
        mockMvc
            .perform(
                get("/api/v1/explore/friends")
                    .by(viewer)
                    .param("friendId", chosen.toString())
                    .param("size", "1")
                    .param("cursor", second.toString()),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.wishes[0].wishId").value(first))
            .andExpect(jsonPath("$.pageResponse.hasNext").value(false))
    }

    @Test
    fun `프로필을 비공개로 바꾼 친구와 차단한 친구는 칩과 목록에서 빠진다`() {
        val mockMvc = mockMvc()
        val viewer = user(IdentityType.MEMBER)
        val wentPrivate = user(IdentityType.MEMBER)
        val blocked = user(IdentityType.MEMBER)
        befriend(mockMvc, viewer, wentPrivate)
        befriend(mockMvc, viewer, blocked)
        readyWish(wentPrivate)
        readyWish(blocked)
        mockMvc
            .perform(patch("/api/v1/users/me").by(wentPrivate).json(mapOf("isProfilePublic" to false)))
            .andExpect(status().isOk)
        mockMvc.perform(post("/api/v1/blocks/$blocked").by(viewer)).andExpect(status().isOk)

        mockMvc
            .perform(get("/api/v1/explore/friends").by(viewer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.friends", hasSize<Any>(0)))
            .andExpect(jsonPath("$.data.wishes", hasSize<Any>(0)))
    }

    @Test
    fun `친구 피드는 비로그인이면 401 이다`() {
        mockMvc()
            .perform(get("/api/v1/explore/friends"))
            .andExpect(status().isUnauthorized)
    }

    private fun mockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    private fun copy(
        mockMvc: MockMvc,
        userId: UUID,
        wishId: Long,
    ) = objectMapper
        .readTree(
            mockMvc
                .perform(post("/api/v1/explore/wishes/$wishId/copy").by(userId))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString,
        ).path("data")

    private fun befriend(
        mockMvc: MockMvc,
        userId: UUID,
        friendId: UUID,
    ) {
        mockMvc.perform(post("/api/v1/friends/$friendId").by(userId)).andExpect(status().isOk)
    }

    private fun user(identityType: IdentityType): UUID =
        UUID.randomUUID().also { userId ->
            jdbcTemplate.update(
                "INSERT INTO users (id, nickname, identity_type, created_at, updated_at) VALUES (?, ?, ?, NOW(6), NOW(6))",
                uuidToBytes(userId),
                userId.toString().take(10),
                identityType.name,
            )
        }

    private fun MockHttpServletRequestBuilder.by(
        userId: UUID,
        identityType: IdentityType = IdentityType.MEMBER,
    ): MockHttpServletRequestBuilder =
        header(HttpHeaders.AUTHORIZATION, "Bearer ${jwtProvider.generateAccessToken(userId, identityType)}")

    private fun MockHttpServletRequestBuilder.json(body: Any): MockHttpServletRequestBuilder =
        contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body))

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
}
