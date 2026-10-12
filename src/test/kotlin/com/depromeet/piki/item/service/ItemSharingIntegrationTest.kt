package com.depromeet.piki.item.service

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.contracts.extraction.v1.ExtractionMethod
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.domain.ItemSlot
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.completeLatest
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.User
import com.depromeet.piki.user.repository.UserJpaRepository
import com.depromeet.piki.wishlist.service.WishPersistenceService
import com.depromeet.piki.wishlist.service.dto.RegisterWishFromUrlCommand
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@Transactional
class ItemSharingIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var wishPersistenceService: WishPersistenceService

    @Autowired private lateinit var itemParsingService: ItemParsingService

    @Autowired private lateinit var userJpaRepository: UserJpaRepository

    @Autowired private lateinit var jwtProvider: JwtProvider

    @Autowired private lateinit var webApplicationContext: WebApplicationContext

    @Autowired private lateinit var entityManager: EntityManager

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `처음 보는 링크는 아이템 없이 담기고 파싱을 요청한다`() {
        val user = newMember()

        val result = wishPersistenceService.persist(RegisterWishFromUrlCommand(user, ProductLink.parse("https://www.musinsa.com/products/8100001")))

        assertNull(result.item)
        assertNull(result.wish.itemId)
        assertEquals(ItemStatus.PARSING, result.card.status)
        assertEquals(1, outboxCountOf(result.wish.slot))
    }

    @Test
    fun `같은 새 링크를 두 사람이 등록하면 진행 중 파싱에 합류하지 않고 각자 파싱을 요청한다`() {
        val url = "https://www.musinsa.com/products/8100002"

        val first = wishPersistenceService.persist(RegisterWishFromUrlCommand(newMember(), ProductLink.parse(url)))
        val second = wishPersistenceService.persist(RegisterWishFromUrlCommand(newMember(), ProductLink.parse(url)))

        assertNull(second.item)
        assertEquals(1, outboxCountOf(first.wish.slot))
        assertEquals(1, outboxCountOf(second.wish.slot))
    }

    @Test
    fun `이미 확정된 링크를 다른 사람이 등록해도 아이템 없이 담기고 새로 파싱한다`() {
        val url = "https://www.musinsa.com/products/8100003"
        registerParsed(newMember(), url)

        val second = wishPersistenceService.persist(RegisterWishFromUrlCommand(newMember(), ProductLink.parse(url)))

        assertNull(second.wish.itemId)
        assertEquals(ItemStatus.PARSING, second.card.status)
        assertEquals(1, outboxCountOf(second.wish.slot))
    }

    @Test
    fun `이미 확정된 링크의 파싱이 성공하면 같은 아이템에 붙고 버전이 하나 더 쌓인다`() {
        val url = "https://www.musinsa.com/products/8100004"
        val itemId = registerParsed(newMember(), url)

        val secondItemId = registerParsed(newMember(), url)

        assertEquals(itemId, secondItemId)
        assertEquals(2, versionCountOf(itemId))
    }

    @Test
    fun `등록 URL 이 달라도 파싱 귀결점이 같으면 같은 아이템에 붙는다`() {
        val finalUrl = "https://www.musinsa.com/products/8100005"
        val itemId = registerParsed(newMember(), "https://musinsa.onelink.me/PvkC/share0001", finalUrl = finalUrl)

        val secondItemId = registerParsed(newMember(), finalUrl)

        assertEquals(itemId, secondItemId)
    }

    // 추적 쿼리가 달라도 같은 정체 URL 로 정규화된다. 문자열 비교였다면 통과했을 재등록
    @Test
    fun `확정된 상품을 같은 사용자가 링크 모양만 바꿔 다시 담으면 409 다`() {
        val user = newMember()
        registerParsed(user, "https://www.musinsa.com/products/8100007")

        mockMvc()
            .perform(
                post("/api/v1/wishlists")
                    .header(HttpHeaders.AUTHORIZATION, bearer(user))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"url": "https://www.musinsa.com/products/8100007?utm_source=kakao"}"""),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("WISH-009"))
            .andExpect(jsonPath("$.detail").value("이미 위시리스트에 등록된 상품이에요."))
    }

    @Test
    fun `단축링크로 담아 확정된 상품을 같은 단축링크로 다시 담으면 요청 이력 기준으로 409 다`() {
        val user = newMember()
        val shortUrl = "https://musinsa.onelink.me/PvkC/share0009"
        registerParsed(user, shortUrl, finalUrl = "https://www.musinsa.com/products/8100009")

        mockMvc()
            .perform(
                post("/api/v1/wishlists")
                    .header(HttpHeaders.AUTHORIZATION, bearer(user))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"url": "$shortUrl"}"""),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("WISH-009"))
    }

    // 단축 코드는 대소문자를 가린다. 대소문자를 무시하는 비교였다면 409 로 막혔을 등록
    @Test
    fun `대소문자만 다른 단축링크는 다른 요청이라 둘 다 담긴다`() {
        val user = newMember()
        wishPersistenceService.persist(RegisterWishFromUrlCommand(user, ProductLink.parse("https://musinsa.onelink.me/PvkC/CaseA1")))
        entityManager.flush()

        mockMvc()
            .perform(
                post("/api/v1/wishlists")
                    .header(HttpHeaders.AUTHORIZATION, bearer(user))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"url": "https://musinsa.onelink.me/PvkC/casea1"}"""),
            ).andExpect(status().isCreated)
    }

    @Test
    fun `파싱 중인 링크를 같은 사용자가 다시 담으면 요청 입력 기준으로 409 다`() {
        val user = newMember()
        wishPersistenceService.persist(RegisterWishFromUrlCommand(user, ProductLink.parse("https://www.musinsa.com/products/8100008")))

        mockMvc()
            .perform(
                post("/api/v1/wishlists")
                    .header(HttpHeaders.AUTHORIZATION, bearer(user))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"url": "https://www.musinsa.com/products/8100008?utm_source=kakao"}"""),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("WISH-009"))
    }

    // 등록 뒤 파싱 성공과 같은 경로로 아이템을 확정한다
    private fun registerParsed(
        userId: UUID,
        url: String,
        finalUrl: String? = url,
    ): Long {
        val result = wishPersistenceService.persist(RegisterWishFromUrlCommand(userId, ProductLink.parse(url)))
        itemParsingService.completeLatest(
            entityManager,
            result.wish.slot,
            ProductSnapshot(
                name = "공유 상품",
                price = 10_000,
                currency = "KRW",
                imageUrl = "https://img.example.com/s.png",
                finalUrl = finalUrl?.let(ProductLink::parse),
                method = ExtractionMethod.STRUCTURED,
            ),
        )
        entityManager.flush()
        entityManager.clear()
        val itemId = jdbcTemplate.queryForObject("SELECT item_id FROM wishes WHERE id = ?", Long::class.java, result.wish.getId())
        return assertNotNull(itemId)
    }

    private fun versionCountOf(itemId: Long): Int =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM item_snapshots WHERE item_id = ?", Int::class.java, itemId) ?: 0

    private fun outboxCountOf(slot: ItemSlot): Int {
        entityManager.flush()
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM item_parse_outbox WHERE slot_type = ? AND slot_id = ?",
            Int::class.java,
            slot.type.name,
            slot.id,
        ) ?: 0
    }

    private fun newMember(): UUID {
        val userId = UUID.randomUUID()
        userJpaRepository.save(
            User(
                id = userId,
                nickname = "곰${userId.toString().take(5)}",
                profileImage = "https://cdn.example.com/p.jpg",
                identityType = IdentityType.MEMBER,
            ),
        )
        return userId
    }

    private fun bearer(userId: UUID): String = "Bearer ${jwtProvider.generateAccessToken(userId, IdentityType.MEMBER)}"

    private fun mockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()
}
