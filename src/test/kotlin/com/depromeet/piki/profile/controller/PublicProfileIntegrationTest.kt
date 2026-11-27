package com.depromeet.piki.profile.controller

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.item.service.ItemParsingService
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.profile.domain.ProfileRelation
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.completeLatest
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.UserErrorCode
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

// 다른 사람 프로필·공개 폴더(#1290). 핵심은 "볼 수 없는 이유가 응답으로 드러나지 않는다" 이다.
@Transactional
class PublicProfileIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var webApplicationContext: WebApplicationContext

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired private lateinit var jwtProvider: JwtProvider

    @Autowired private lateinit var entityManager: EntityManager

    @Autowired private lateinit var wishPersistenceService: WishPersistenceService

    @Autowired private lateinit var itemParsingService: ItemParsingService

    @Test
    fun `공개 프로필은 공개 폴더만 내려주고 비공개 폴더는 빠진다`() {
        val mockMvc = mockMvc()
        val viewer = member()
        val owner = member()
        val open = createFolder(owner, "공개 폴더")
        putWish(owner, open, readyWish(owner))
        val hidden = createFolder(owner, "비공개 폴더")
        hide(owner, hidden)

        mockMvc
            .perform(get("/api/v1/profiles/$owner").by(viewer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.relation").value(ProfileRelation.NOT_FRIEND.name))
            .andExpect(jsonPath("$.data.foldersHidden").value(false))
            .andExpect(jsonPath("$.data.folders", hasSize<Any>(1)))
            .andExpect(jsonPath("$.data.folders[0].folderId").value(open))
            // 표지는 내 폴더 목록과 같은 계산이다
            .andExpect(jsonPath("$.data.folders[0].coverImageUrls", hasSize<Any>(1)))
            .andExpect(jsonPath("$.data.folders[0].coverImageUrls[0]").value("https://img.example.com/a.png"))
    }

    @Test
    fun `비로그인도 공개 프로필을 볼 수 있다`() {
        val mockMvc = mockMvc()
        val owner = member()
        createFolder(owner, "공개 폴더")

        mockMvc
            .perform(get("/api/v1/profiles/$owner"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.relation").value(ProfileRelation.NOT_FRIEND.name))
            .andExpect(jsonPath("$.data.folders", hasSize<Any>(1)))
    }

    @Test
    fun `프로필을 비공개로 두면 닉네임은 보이고 폴더만 빠진다`() {
        val mockMvc = mockMvc()
        val viewer = member()
        val owner = member()
        createFolder(owner, "공개 폴더")
        makeProfilePrivate(owner)

        mockMvc
            .perform(get("/api/v1/profiles/$owner").by(viewer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.nickname").exists())
            .andExpect(jsonPath("$.data.profileImage").exists())
            .andExpect(jsonPath("$.data.foldersHidden").value(true))
            .andExpect(jsonPath("$.data.folders", hasSize<Any>(0)))
    }

    // 공개한 폴더가 없는 사람은 비공개 프로필과 다른 화면(2-2a)이라 foldersHidden 으로 갈린다(#1302)
    @Test
    fun `공개한 폴더가 없으면 폴더는 빈 배열이고 가려지지 않은 것으로 내려간다`() {
        val mockMvc = mockMvc()
        val owner = member()
        hide(owner, createFolder(owner, "비공개 폴더"))

        mockMvc
            .perform(get("/api/v1/profiles/$owner").by(member()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.foldersHidden").value(false))
            .andExpect(jsonPath("$.data.folders", hasSize<Any>(0)))
    }

    // 가르면 "저 사람이 나를 차단했다" 가 드러난다. 비공개 프로필과 **같은 응답**이어야 한다
    @Test
    fun `상대가 나를 차단한 경우와 비공개 프로필의 응답이 같다`() {
        val mockMvc = mockMvc()
        val viewer = member()
        val blocker = member()
        val privateOwner = member()
        createFolder(blocker, "공개 폴더")
        createFolder(privateOwner, "공개 폴더")
        mockMvc.perform(post("/api/v1/blocks/$viewer").by(blocker)).andExpect(status().isOk)
        makeProfilePrivate(privateOwner)

        val blocked = bodyOf(mockMvc, "/api/v1/profiles/$blocker", viewer)
        val private = bodyOf(mockMvc, "/api/v1/profiles/$privateOwner", viewer)

        // 관계·폴더 노출이 똑같아야 한다. 닉네임 등 사람마다 다른 값은 비교 대상이 아니다
        listOf(blocked, private).forEach {
            val data = objectMapper.readTree(it)["data"]
            assert(data["relation"].asText() == ProfileRelation.NOT_FRIEND.name) { "관계가 드러나면 안 된다: $data" }
            assert(data["folders"].size() == 0) { "폴더가 보이면 안 된다: $data" }
            assert(data["foldersHidden"].asBoolean()) { "비공개 프로필과 같이 가려져야 한다: $data" }
        }
    }

    // 내가 건 차단은 내가 한 일이라 숨기지 않는다 — 화면에 차단 해제 버튼이 떠야 한다
    @Test
    fun `내가 차단한 사람은 관계가 BLOCKED 로 내려간다`() {
        val mockMvc = mockMvc()
        val viewer = member()
        val owner = member()
        createFolder(owner, "공개 폴더")
        mockMvc.perform(post("/api/v1/blocks/$owner").by(viewer)).andExpect(status().isOk)

        mockMvc
            .perform(get("/api/v1/profiles/$owner").by(viewer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.relation").value(ProfileRelation.BLOCKED.name))
            .andExpect(jsonPath("$.data.foldersHidden").value(true))
            .andExpect(jsonPath("$.data.folders", hasSize<Any>(0)))
    }

    @Test
    fun `친구면 관계가 FRIEND 이고 본인이면 SELF 다`() {
        val mockMvc = mockMvc()
        val viewer = member()
        val friend = member()
        mockMvc.perform(post("/api/v1/friends/$friend").by(viewer)).andExpect(status().isOk)

        mockMvc
            .perform(get("/api/v1/profiles/$friend").by(viewer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.relation").value(ProfileRelation.FRIEND.name))
        mockMvc
            .perform(get("/api/v1/profiles/$viewer").by(viewer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.relation").value(ProfileRelation.SELF.name))
    }

    @Test
    fun `본인은 자기 비공개 폴더도 프로필에서 본다`() {
        val mockMvc = mockMvc()
        val owner = member()
        val hidden = createFolder(owner, "비공개 폴더")
        hide(owner, hidden)

        mockMvc
            .perform(get("/api/v1/profiles/$owner").by(owner))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.folders", hasSize<Any>(1)))
    }

    @Test
    fun `없는 사용자와 탈퇴한 사용자는 같은 404 다`() {
        val mockMvc = mockMvc()
        val viewer = member()
        val withdrawn = member()
        jdbcTemplate.update("UPDATE users SET deleted_at = NOW(6) WHERE id = ?", uuidToBytes(withdrawn))

        mockMvc
            .perform(get("/api/v1/profiles/${UUID.randomUUID()}").by(viewer))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(UserErrorCode.NOT_FOUND.code))
        mockMvc
            .perform(get("/api/v1/profiles/$withdrawn").by(viewer))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(UserErrorCode.NOT_FOUND.code))
    }

    @Test
    fun `공개 폴더 상세는 그 안의 위시를 내 목록과 같은 모양으로 내려준다`() {
        val mockMvc = mockMvc()
        val viewer = member()
        val owner = member()
        val folderId = createFolder(owner, "공개 폴더")
        putWish(owner, folderId, readyWish(owner))

        mockMvc
            .perform(get("/api/v1/profiles/$owner/folders/$folderId").by(viewer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.folderId").value(folderId))
            .andExpect(jsonPath("$.data.wishes", hasSize<Any>(1)))
            .andExpect(jsonPath("$.data.wishes[0].item.name").exists())
            .andExpect(jsonPath("$.data.wishes[0].saved").value(false))
    }

    @Test
    fun `공개 폴더 상세의 위시는 내가 이미 담은 상품이면 saved 가 true 다`() {
        val mockMvc = mockMvc()
        val viewer = member()
        val owner = member()
        val folderId = createFolder(owner, "공개 폴더")
        val saved = readyWish(owner)
        putWish(owner, folderId, saved)
        putWish(owner, folderId, readyWish(owner))
        val viewersWish = copy(viewer, saved)

        mockMvc
            .perform(get("/api/v1/profiles/$owner/folders/$folderId").by(viewer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.wishes", hasSize<Any>(2)))
            .andExpect(jsonPath("$.data.wishes[?(@.wishId == $saved)].saved").value(true))
            .andExpect(jsonPath("$.data.wishes[?(@.wishId != $saved)].saved").value(false))
            .andExpect(jsonPath("$.data.wishes[?(@.wishId == $saved)].savedWishId").value(contains(viewersWish.toInt())))
            .andExpect(jsonPath("$.data.wishes[?(@.wishId != $saved)].savedWishId").value(contains(nullValue())))
        // 비로그인은 담은 상품이 없다
        mockMvc
            .perform(get("/api/v1/profiles/$owner/folders/$folderId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.wishes[?(@.wishId == $saved)].saved").value(false))
    }

    // 탐색 전부터 가진 위시와 탐색에서 담은 위시는 같은 위시 하나라, 취소가 곧 그 위시 삭제다
    @Test
    fun `하트를 savedWishId 로 취소하면 그 위시가 내 폴더에서도 빠진다`() {
        val mockMvc = mockMvc()
        val viewer = member()
        val owner = member()
        val ownersFolder = createFolder(owner, "주인 폴더")
        val ownersWish = readyWish(owner)
        putWish(owner, ownersFolder, ownersWish)
        val viewersWish = copy(viewer, ownersWish)
        val viewersFolder = createFolder(viewer, "내 폴더")
        putWish(viewer, viewersFolder, viewersWish)

        mockMvc.perform(delete("/api/v1/wishlists/$viewersWish").by(viewer)).andExpect(status().isOk)

        mockMvc
            .perform(get("/api/v1/profiles/$owner/folders/$ownersFolder").by(viewer))
            .andExpect(jsonPath("$.data.wishes[0].saved").value(false))
            .andExpect(jsonPath("$.data.wishes[0].savedWishId").value(nullValue()))
        mockMvc
            .perform(get("/api/v1/profiles/$viewer/folders/$viewersFolder").by(viewer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.wishes", hasSize<Any>(0)))
    }

    // 볼 수 없는 네 가지 이유가 모두 같은 404 여야 한다
    @Test
    fun `비공개 폴더와 차단과 프로필 비공개가 모두 같은 404 다`() {
        val mockMvc = mockMvc()
        val viewer = member()

        val hiddenOwner = member()
        val hiddenFolder = createFolder(hiddenOwner, "비공개 폴더")
        hide(hiddenOwner, hiddenFolder)

        val blocker = member()
        val blockerFolder = createFolder(blocker, "공개 폴더")
        mockMvc.perform(post("/api/v1/blocks/$viewer").by(blocker)).andExpect(status().isOk)

        val privateOwner = member()
        val privateFolder = createFolder(privateOwner, "공개 폴더")
        makeProfilePrivate(privateOwner)

        listOf(
            "/api/v1/profiles/$hiddenOwner/folders/$hiddenFolder",
            "/api/v1/profiles/$blocker/folders/$blockerFolder",
            "/api/v1/profiles/$privateOwner/folders/$privateFolder",
            "/api/v1/profiles/$hiddenOwner/folders/999999",
        ).forEach { path ->
            mockMvc
                .perform(get(path).by(viewer))
                .andExpect(status().isNotFound)
                .andExpect(jsonPath("$.code").value(UserErrorCode.NOT_FOUND.code))
        }
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────────────

    private fun mockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    private fun bodyOf(
        mockMvc: MockMvc,
        path: String,
        viewer: UUID,
    ): String =
        mockMvc
            .perform(get(path).by(viewer))
            .andExpect(status().isOk)
            .andReturn()
            .response.contentAsString

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
                RegisterWishFromUrlCommand(
                    userId,
                    ProductLink.parse("https://shop.example.com/p/${UUID.randomUUID()}"),
                ),
            )
        itemParsingService.completeLatest(
            entityManager,
            result.wish.slot,
            ProductSnapshot(name = "상품", price = 10_000, currency = "KRW", imageUrl = "https://img.example.com/a.png"),
        )
        return result.wish.getId()
    }

    private fun copy(
        userId: UUID,
        wishId: Long,
    ): Long {
        val response =
            mockMvc()
                .perform(post("/api/v1/explore/wishes/$wishId/copy").by(userId))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return objectMapper
            .readTree(response)
            .path("data")
            .path("wishId")
            .asLong()
    }

    private fun createFolder(
        userId: UUID,
        name: String,
    ): Long {
        val response =
            mockMvc()
                .perform(
                    post("/api/v1/folders").by(userId).json(mapOf("name" to name)),
                ).andExpect(status().isCreated)
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
            .perform(
                post("/api/v1/folders/$folderId/wishes").by(userId).json(mapOf("wishIds" to listOf(wishId))),
            ).andExpect(status().isOk)
    }

    private fun hide(
        userId: UUID,
        folderId: Long,
    ) {
        mockMvc()
            .perform(
                patch("/api/v1/folders/$folderId/visibility").by(userId).json(mapOf("isPublic" to false)),
            ).andExpect(status().isOk)
    }

    private fun makeProfilePrivate(userId: UUID) {
        mockMvc()
            .perform(
                patch("/api/v1/users/me").by(userId).json(mapOf("isProfilePublic" to false)),
            ).andExpect(status().isOk)
    }
}
