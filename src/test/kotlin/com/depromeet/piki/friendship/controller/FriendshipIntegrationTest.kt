package com.depromeet.piki.friendship.controller

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.friendship.domain.FriendshipErrorCode
import com.depromeet.piki.item.service.ItemParsingService
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.completeLatest
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.UserErrorCode
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

// 단방향 친구(#1288)의 계약. 추가·삭제의 멱등과 "목록 행의 수는 공개 폴더 기준" 을 고정한다.
@Transactional
class FriendshipIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var webApplicationContext: WebApplicationContext

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired private lateinit var jwtProvider: JwtProvider

    @Autowired private lateinit var entityManager: EntityManager

    @Autowired private lateinit var wishPersistenceService: WishPersistenceService

    @Autowired private lateinit var itemParsingService: ItemParsingService

    @Test
    fun `친구 추가는 내 목록에만 생기고 상대 목록은 그대로다`() {
        val mockMvc = mockMvc()
        val me = member()
        val friend = member()

        mockMvc.perform(post("/api/v1/friends/$friend").by(me)).andExpect(status().isOk)

        mockMvc
            .perform(get("/api/v1/friends").by(me))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.friendCount").value(1))
            .andExpect(jsonPath("$.data.friends[0].userId").value(friend.toString()))
        // 단방향이라 상대에게는 아무 일도 일어나지 않는다
        mockMvc
            .perform(get("/api/v1/friends").by(friend))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.friendCount").value(0))
    }

    @Test
    fun `같은 친구를 두 번 추가해도 200 이고 목록에 한 번만 남는다`() {
        val mockMvc = mockMvc()
        val me = member()
        val friend = member()

        mockMvc.perform(post("/api/v1/friends/$friend").by(me)).andExpect(status().isOk)
        mockMvc.perform(post("/api/v1/friends/$friend").by(me)).andExpect(status().isOk)

        mockMvc
            .perform(get("/api/v1/friends").by(me))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.friends", hasSize<Any>(1)))
    }

    @Test
    fun `친구가 아닌 사람을 삭제해도 200 이다`() {
        val mockMvc = mockMvc()
        val me = member()
        val stranger = member()

        mockMvc.perform(delete("/api/v1/friends/$stranger").by(me)).andExpect(status().isOk)
    }

    @Test
    fun `자기 자신은 친구로 추가할 수 없다`() {
        val mockMvc = mockMvc()
        val me = member()

        mockMvc
            .perform(post("/api/v1/friends/$me").by(me))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value(FriendshipErrorCode.SELF_NOT_ALLOWED.code))
    }

    @Test
    fun `없는 사용자를 친구로 추가하면 404 다`() {
        val mockMvc = mockMvc()
        val me = member()

        mockMvc
            .perform(post("/api/v1/friends/${UUID.randomUUID()}").by(me))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(UserErrorCode.NOT_FOUND.code))
    }

    @Test
    fun `게스트는 친구 기능을 쓸 수 없다`() {
        val mockMvc = mockMvc()
        val guest = UUID.randomUUID().also { insertUser(it, IdentityType.GUEST) }
        val friend = member()

        mockMvc
            .perform(post("/api/v1/friends/$friend").by(guest, IdentityType.GUEST))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value(FriendshipErrorCode.GUEST_CANNOT_USE.code))
        mockMvc
            .perform(get("/api/v1/friends").by(guest, IdentityType.GUEST))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value(FriendshipErrorCode.GUEST_CANNOT_USE.code))
    }

    @Test
    fun `목록의 수는 공개 폴더와 그 안의 위시만 센다`() {
        val mockMvc = mockMvc()
        val me = member()
        val friend = member()
        // 공개 폴더에 위시 하나, 비공개 폴더에 위시 하나. 비공개 쪽은 폴더도 위시도 세지 않는다
        val publicFolder = createFolder(friend, "공개 폴더")
        val privateFolder = createFolder(friend, "비공개 폴더")
        putWish(friend, publicFolder, readyWish(friend))
        putWish(friend, privateFolder, readyWish(friend))
        hide(friend, privateFolder)
        // 폴더에 안 든 위시는 프로필에서 보이지 않으므로 세지 않는다
        readyWish(friend)
        mockMvc.perform(post("/api/v1/friends/$friend").by(me)).andExpect(status().isOk)

        mockMvc
            .perform(get("/api/v1/friends").by(me))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.friends[0].folderCount").value(1))
            .andExpect(jsonPath("$.data.friends[0].wishCount").value(1))
    }

    @Test
    fun `프로필을 비공개로 둔 친구는 수가 내려가지 않는다`() {
        val mockMvc = mockMvc()
        val me = member()
        val friend = member()
        putWish(friend, createFolder(friend, "공개 폴더"), readyWish(friend))
        mockMvc
            .perform(patch("/api/v1/users/me").by(friend).json(mapOf("isProfilePublic" to false)))
            .andExpect(status().isOk)
        mockMvc.perform(post("/api/v1/friends/$friend").by(me)).andExpect(status().isOk)

        // 0 이 아니라 아예 없다 — "공개했는데 비어 있음" 과 구분된다
        mockMvc
            .perform(get("/api/v1/friends").by(me))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.friends[0].folderCount").doesNotExist())
            .andExpect(jsonPath("$.data.friends[0].wishCount").doesNotExist())
    }

    @Test
    fun `목록은 최근 추가순이다`() {
        val mockMvc = mockMvc()
        val me = member()
        val first = member()
        val second = member()

        mockMvc.perform(post("/api/v1/friends/$first").by(me)).andExpect(status().isOk)
        mockMvc.perform(post("/api/v1/friends/$second").by(me)).andExpect(status().isOk)

        mockMvc
            .perform(get("/api/v1/friends").by(me))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.friends[0].userId").value(second.toString()))
            .andExpect(jsonPath("$.data.friends[1].userId").value(first.toString()))
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────────────

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

    private fun createFolder(
        userId: UUID,
        name: String,
    ): Long {
        val response =
            mockMvc()
                .perform(
                    post("/api/v1/folders").by(userId).json(mapOf("name" to name)),
                )
                .andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(response).path("data").path("id").asLong()
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

    private fun hide(
        userId: UUID,
        folderId: Long,
    ) {
        mockMvc()
            .perform(patch("/api/v1/folders/$folderId/visibility").by(userId).json(mapOf("isPublic" to false)))
            .andExpect(status().isOk)
    }
}
