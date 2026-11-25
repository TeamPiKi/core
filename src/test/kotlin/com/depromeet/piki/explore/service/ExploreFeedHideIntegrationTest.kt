package com.depromeet.piki.explore.service

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.explore.repository.ExploreFeedStore
import com.depromeet.piki.item.service.ItemParsingService
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.completeLatest
import com.depromeet.piki.support.deleteWishDataOf
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.WebApplicationContext
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// 커밋 뒤에 도는 리스너를 보려면 실제로 커밋해야 한다. 그래서 @Transactional 을 두지 않고 만든 행을 끝에서 지운다
class ExploreFeedHideIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var webApplicationContext: WebApplicationContext

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired private lateinit var jwtProvider: JwtProvider

    @Autowired private lateinit var entityManager: EntityManager

    @Autowired private lateinit var transactionTemplate: TransactionTemplate

    @Autowired private lateinit var wishPersistenceService: WishPersistenceService

    @Autowired private lateinit var itemParsingService: ItemParsingService

    @Autowired private lateinit var exploreFeedRefresher: ExploreFeedRefresher

    @Autowired private lateinit var exploreFeedStore: ExploreFeedStore

    @Test
    fun `프로필을 비공개로 바꾸면 다음 주기를 기다리지 않고 피드에서 빠진다`() {
        val owner = UUID.randomUUID()
        try {
            jdbcTemplate.update(
                "INSERT INTO users (id, nickname, identity_type, created_at, updated_at) VALUES (?, ?, ?, NOW(6), NOW(6))",
                uuidToBytes(owner),
                owner.toString().take(10),
                IdentityType.MEMBER.name,
            )
            val wishId =
                requireNotNull(
                    transactionTemplate.execute {
                        val result =
                            wishPersistenceService.persist(
                                RegisterWishFromUrlCommand(owner, ProductLink.parse("https://shop.example.com/p/${UUID.randomUUID()}")),
                            )
                        itemParsingService.completeLatest(
                            entityManager,
                            result.wish.slot,
                            ProductSnapshot(name = "상품", price = 10_000, currency = "KRW", imageUrl = "https://img.example.com/a.png"),
                        )
                        result.wish.getId()
                    },
                )
            exploreFeedRefresher.refresh()
            assertTrue(wishId in feedWishIds())

            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
                .perform(
                    patch("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${jwtProvider.generateAccessToken(owner, IdentityType.MEMBER)}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"isProfilePublic":false}"""),
                ).andExpect(status().isOk)

            assertFalse(wishId in feedWishIds())
        } finally {
            jdbcTemplate.deleteWishDataOf(owner)
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", uuidToBytes(owner))
            exploreFeedRefresher.refresh()
        }
    }

    private fun feedWishIds(): Set<Long> = assertNotNull(exploreFeedStore.find()).cards.map { it.wishId }.toSet()
}
