package com.depromeet.piki.explore.controller

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.explore.repository.ExploreFeedStore
import com.depromeet.piki.explore.service.ExploreFeedRefresher
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
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import java.util.UUID

// 다른 테스트가 커밋한 위시도 피드에 섞인다. 커서를 이 테스트가 만든 위시 바로 위에 두어 그 구간만 본다
@Transactional
class ExploreRecentIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var webApplicationContext: WebApplicationContext

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired private lateinit var jwtProvider: JwtProvider

    @Autowired private lateinit var entityManager: EntityManager

    @Autowired private lateinit var wishPersistenceService: WishPersistenceService

    @Autowired private lateinit var itemParsingService: ItemParsingService

    @Autowired private lateinit var exploreFeedRefresher: ExploreFeedRefresher

    @Autowired private lateinit var redisTemplate: StringRedisTemplate

    @Test
    fun `비로그인도 최근에 담긴 순으로 카드를 받는다`() {
        val mockMvc = mockMvc()
        val owner = member()
        val older = readyWish(owner, "https://shop.example.com/p/${UUID.randomUUID()}")
        val newer = readyWish(owner, "https://shop.example.com/p/${UUID.randomUUID()}")
        exploreFeedRefresher.refresh()

        mockMvc
            .perform(get("/api/v1/explore/recent").param("cursor", (newer + 1).toString()).param("size", "2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.updatedAt").exists())
            .andExpect(jsonPath("$.data.wishes", hasSize<Any>(2)))
            .andExpect(jsonPath("$.data.wishes[0].wishId").value(newer))
            .andExpect(jsonPath("$.data.wishes[1].wishId").value(older))
            .andExpect(jsonPath("$.data.wishes[0].addedAt").exists())
            .andExpect(jsonPath("$.data.wishes[0].saved").value(false))
            .andExpect(jsonPath("$.data.wishes[0].item.id").exists())
            .andExpect(jsonPath("$.data.wishes[0].item.name").value("상품"))
            .andExpect(jsonPath("$.data.wishes[0].item.price").value(10_000))
            .andExpect(jsonPath("$.data.wishes[0].item.imageUrl").value("https://img.example.com/a.png"))
            .andExpect(jsonPath("$.data.wishes[0].item.sourceUrl").exists())
    }

    @Test
    fun `크기만큼 내주고 다음 쪽 커서로 이어 읽는다`() {
        val mockMvc = mockMvc()
        val owner = member()
        val first = readyWish(owner, "https://shop.example.com/p/${UUID.randomUUID()}")
        val second = readyWish(owner, "https://shop.example.com/p/${UUID.randomUUID()}")
        val third = readyWish(owner, "https://shop.example.com/p/${UUID.randomUUID()}")
        exploreFeedRefresher.refresh()

        mockMvc
            .perform(get("/api/v1/explore/recent").param("cursor", (third + 1).toString()).param("size", "2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.wishes[0].wishId").value(third))
            .andExpect(jsonPath("$.data.wishes[1].wishId").value(second))
            .andExpect(jsonPath("$.pageResponse.hasNext").value(true))
            .andExpect(jsonPath("$.pageResponse.nextCursor").value(second.toString()))

        mockMvc
            .perform(get("/api/v1/explore/recent").param("cursor", second.toString()).param("size", "1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.wishes[0].wishId").value(first))

        mockMvc
            .perform(get("/api/v1/explore/recent").param("cursor", "0"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.wishes", hasSize<Any>(0)))
            .andExpect(jsonPath("$.pageResponse.hasNext").value(false))
            .andExpect(jsonPath("$.pageResponse.nextCursor").value(nullValue()))
    }

    @Test
    fun `내가 이미 가진 상품의 카드는 saved 가 true 다`() {
        val mockMvc = mockMvc()
        val owner = member()
        val viewer = member()
        val link = "https://shop.example.com/p/${UUID.randomUUID()}"
        val viewersWish = readyWish(viewer, link)
        val ownersWish = readyWish(owner, link)
        val othersWish = readyWish(owner, "https://shop.example.com/p/${UUID.randomUUID()}")
        exploreFeedRefresher.refresh()

        mockMvc
            .perform(get("/api/v1/explore/recent").by(viewer).param("cursor", (othersWish + 1).toString()).param("size", "2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.wishes[0].wishId").value(othersWish))
            .andExpect(jsonPath("$.data.wishes[0].saved").value(false))
            .andExpect(jsonPath("$.data.wishes[0].savedWishId").value(nullValue()))
            .andExpect(jsonPath("$.data.wishes[1].wishId").value(ownersWish))
            .andExpect(jsonPath("$.data.wishes[1].saved").value(true))
            .andExpect(jsonPath("$.data.wishes[1].savedWishId").value(viewersWish))
    }

    @Test
    fun `차단 관계인 사람의 카드는 어느 쪽이 차단했든 빠진다`() {
        val mockMvc = mockMvc()
        val viewer = member()
        val blockedByMe = member()
        val blockedMe = member()
        val stranger = member()
        val visible = readyWish(stranger, "https://shop.example.com/p/${UUID.randomUUID()}")
        readyWish(blockedByMe, "https://shop.example.com/p/${UUID.randomUUID()}")
        val last = readyWish(blockedMe, "https://shop.example.com/p/${UUID.randomUUID()}")
        mockMvc.perform(post("/api/v1/blocks/$blockedByMe").by(viewer)).andExpect(status().isOk)
        mockMvc.perform(post("/api/v1/blocks/$viewer").by(blockedMe)).andExpect(status().isOk)
        exploreFeedRefresher.refresh()

        mockMvc
            .perform(get("/api/v1/explore/recent").by(viewer).param("cursor", (last + 1).toString()).param("size", "1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.wishes[0].wishId").value(visible))
    }

    @Test
    fun `같은 상품을 가장 최근에 담은 사람이 차단 관계면 먼저 담은 사람의 카드가 나온다`() {
        val mockMvc = mockMvc()
        val viewer = member()
        val earlier = member()
        val blocked = member()
        val link = "https://shop.example.com/p/${UUID.randomUUID()}"
        val earlierWish = readyWish(earlier, link)
        val blockedWish = readyWish(blocked, link)
        mockMvc.perform(post("/api/v1/blocks/$blocked").by(viewer)).andExpect(status().isOk)
        exploreFeedRefresher.refresh()

        mockMvc
            .perform(get("/api/v1/explore/recent").by(viewer).param("cursor", (blockedWish + 1).toString()).param("size", "1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.wishes[0].wishId").value(earlierWish))
    }

    @Test
    fun `피드가 아직 만들어지지 않았으면 빈 목록이다`() {
        redisTemplate.delete(ExploreFeedStore.RECENT_KEY)

        mockMvc()
            .perform(get("/api/v1/explore/recent"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.updatedAt").value(nullValue()))
            .andExpect(jsonPath("$.data.wishes", hasSize<Any>(0)))
            .andExpect(jsonPath("$.pageResponse.hasNext").value(false))
    }

    @Test
    fun `숫자가 아닌 커서는 400 이다`() {
        mockMvc()
            .perform(get("/api/v1/explore/recent").param("cursor", "abc"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value(WishErrorCode.INVALID_CURSOR.code))
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
}
