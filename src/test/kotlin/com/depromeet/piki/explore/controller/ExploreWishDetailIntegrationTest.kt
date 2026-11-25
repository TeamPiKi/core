package com.depromeet.piki.explore.controller

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.item.service.ItemParsingService
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.profile.domain.ProfileRelation
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
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
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
import kotlin.test.assertFalse

// 핵심은 "상품은 항상 보이고, 담은 사람과 폴더만 가려진다" 와 "비공개 폴더는 이름도 id 도 새지 않는다" 이다
@Transactional
class ExploreWishDetailIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var webApplicationContext: WebApplicationContext

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired private lateinit var jwtProvider: JwtProvider

    @Autowired private lateinit var entityManager: EntityManager

    @Autowired private lateinit var wishPersistenceService: WishPersistenceService

    @Autowired private lateinit var itemParsingService: ItemParsingService

    @Test
    fun `비로그인도 상품과 담은 사람과 가장 최근에 넣은 공개 폴더를 본다`() {
        val mockMvc = mockMvc()
        val owner = member()
        val wishId = readyWish(owner, newLink())
        putWish(owner, createFolder(owner, "먼저 넣은 폴더"), wishId)
        val latest = createFolder(owner, "나중에 넣은 폴더")
        putWish(owner, latest, wishId)

        mockMvc
            .perform(get("/api/v1/explore/wishes/$wishId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.wishId").value(wishId))
            .andExpect(jsonPath("$.data.addedAt").exists())
            .andExpect(jsonPath("$.data.saved").value(false))
            .andExpect(jsonPath("$.data.savedUserCount").value(1))
            .andExpect(jsonPath("$.data.item.name").value("상품"))
            .andExpect(jsonPath("$.data.item.price").value(10_000))
            .andExpect(jsonPath("$.data.owner.userId").value(owner.toString()))
            .andExpect(jsonPath("$.data.owner.nickname").exists())
            .andExpect(jsonPath("$.data.owner.relation").value(ProfileRelation.NOT_FRIEND.name))
            .andExpect(jsonPath("$.data.owner.folder.folderId").value(latest))
            .andExpect(jsonPath("$.data.owner.folder.name").value("나중에 넣은 폴더"))
    }

    @Test
    fun `비공개 폴더는 내려가지 않고 공개 폴더에도 들었으면 공개 폴더가 내려간다`() {
        val mockMvc = mockMvc()
        val owner = member()
        val viewer = member()
        val onlyHidden = readyWish(owner, newLink())
        val both = readyWish(owner, newLink())
        val open = createFolder(owner, "공개 폴더")
        putWish(owner, open, both)
        val hidden = createFolder(owner, "비공개 폴더")
        putWish(owner, hidden, onlyHidden)
        putWish(owner, hidden, both)
        hide(owner, hidden)

        mockMvc
            .perform(get("/api/v1/explore/wishes/$onlyHidden").by(viewer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.item.name").value("상품"))
            .andExpect(jsonPath("$.data.owner.userId").value(owner.toString()))
            .andExpect(jsonPath("$.data.owner.folder").value(nullValue()))
        mockMvc
            .perform(get("/api/v1/explore/wishes/$both").by(viewer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.owner.folder.folderId").value(open))
    }

    @Test
    fun `프로필이 비공개인 사람의 위시는 상품만 보이고 담은 사람과 그 사람이 직접 입력한 값은 가려진다`() {
        val mockMvc = mockMvc()
        val owner = member()
        val wishId = readyWish(owner, newLink())
        putWish(owner, createFolder(owner, "공개 폴더"), wishId)
        mockMvc
            .perform(
                multipart(HttpMethod.PATCH, "/api/v1/wishlists/$wishId")
                    .param("name", "내가 붙인 이름")
                    .header(HttpHeaders.AUTHORIZATION, bearer(owner)),
            )
            .andExpect(status().isOk)
        makeProfilePrivate(owner)

        val body =
            mockMvc
                .perform(get("/api/v1/explore/wishes/$wishId").by(member()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.data.item.name").value("상품"))
                .andExpect(jsonPath("$.data.owner").value(nullValue()))
                .andReturn()
                .response.contentAsString
        assertFalse(owner.toString() in body)
    }

    @Test
    fun `내 위시를 내가 보면 프로필이 비공개여도 담은 사람으로 내려간다`() {
        val mockMvc = mockMvc()
        val owner = member()
        val wishId = readyWish(owner, newLink())
        makeProfilePrivate(owner)

        mockMvc
            .perform(get("/api/v1/explore/wishes/$wishId").by(owner))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.owner.relation").value(ProfileRelation.SELF.name))
            .andExpect(jsonPath("$.data.saved").value(true))
    }

    @Test
    fun `차단 관계면 어느 쪽이 차단했든 담은 사람이 가려진다`() {
        val mockMvc = mockMvc()
        val viewer = member()
        val blockedByMe = member()
        val blockedMe = member()
        val mine = readyWish(blockedByMe, newLink())
        val theirs = readyWish(blockedMe, newLink())
        mockMvc.perform(post("/api/v1/blocks/$blockedByMe").by(viewer)).andExpect(status().isOk)
        mockMvc.perform(post("/api/v1/blocks/$viewer").by(blockedMe)).andExpect(status().isOk)

        mockMvc
            .perform(get("/api/v1/explore/wishes/$mine").by(viewer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.owner").value(nullValue()))
        mockMvc
            .perform(get("/api/v1/explore/wishes/$theirs").by(viewer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.owner").value(nullValue()))
    }

    @Test
    fun `친구가 담았고 나도 가진 상품이면 관계와 담은 사람 수와 saved 가 반영된다`() {
        val mockMvc = mockMvc()
        val viewer = member()
        val friend = member()
        val link = newLink()
        readyWish(viewer, link)
        val friendsWish = readyWish(friend, link)
        mockMvc.perform(post("/api/v1/friends/$friend").by(viewer)).andExpect(status().isOk)

        mockMvc
            .perform(get("/api/v1/explore/wishes/$friendsWish").by(viewer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.owner.relation").value(ProfileRelation.FRIEND.name))
            .andExpect(jsonPath("$.data.saved").value(true))
            .andExpect(jsonPath("$.data.savedUserCount").value(2))
    }

    @Test
    fun `없는 위시와 지운 위시와 상품이 아직 정해지지 않은 위시는 404 다`() {
        val mockMvc = mockMvc()
        val owner = member()
        val deleted = readyWish(owner, newLink())
        mockMvc.perform(delete("/api/v1/wishlists/$deleted").by(owner)).andExpect(status().isOk)
        mockMvc
            .perform(get("/api/v1/explore/wishes/$deleted"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(WishErrorCode.NOT_FOUND.code))
        val parsing =
            wishPersistenceService
                .persist(RegisterWishFromUrlCommand(owner, ProductLink.parse(newLink())))
                .wish
                .getId()

        mockMvc
            .perform(get("/api/v1/explore/wishes/${Long.MAX_VALUE}"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(WishErrorCode.NOT_FOUND.code))
        mockMvc
            .perform(get("/api/v1/explore/wishes/$parsing"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(WishErrorCode.NOT_FOUND.code))
    }

    private fun mockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    private fun newLink(): String = "https://shop.example.com/p/${UUID.randomUUID()}"

    private fun member(): UUID =
        UUID.randomUUID().also { userId ->
            jdbcTemplate.update(
                "INSERT INTO users (id, nickname, identity_type, created_at, updated_at) VALUES (?, ?, ?, NOW(6), NOW(6))",
                uuidToBytes(userId),
                userId.toString().take(10),
                IdentityType.MEMBER.name,
            )
        }

    private fun bearer(userId: UUID): String = "Bearer ${jwtProvider.generateAccessToken(userId, IdentityType.MEMBER)}"

    private fun MockHttpServletRequestBuilder.by(userId: UUID): MockHttpServletRequestBuilder =
        header(HttpHeaders.AUTHORIZATION, bearer(userId))

    private fun MockHttpServletRequestBuilder.json(body: Any): MockHttpServletRequestBuilder =
        contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body))

    private fun readyWish(
        userId: UUID,
        link: String,
    ): Long {
        val result = wishPersistenceService.persist(RegisterWishFromUrlCommand(userId, ProductLink.parse(link)))
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

    private fun hide(
        userId: UUID,
        folderId: Long,
    ) {
        mockMvc()
            .perform(patch("/api/v1/folders/$folderId/visibility").by(userId).json(mapOf("isPublic" to false)))
            .andExpect(status().isOk)
    }

    private fun makeProfilePrivate(userId: UUID) {
        mockMvc()
            .perform(patch("/api/v1/users/me").by(userId).json(mapOf("isProfilePublic" to false)))
            .andExpect(status().isOk)
    }
}
