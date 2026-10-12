package com.depromeet.piki.wishlist.controller

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.item.domain.ItemParseOutbox
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.domain.ParseInput
import com.depromeet.piki.item.domain.ParsePurpose
import com.depromeet.piki.item.repository.ItemJpaRepository
import com.depromeet.piki.item.repository.ItemParseOutboxRepository
import com.depromeet.piki.item.repository.ItemSnapshotJpaRepository
import com.depromeet.piki.item.service.ExtractionSettlement
import com.depromeet.piki.item.service.ItemParsingService
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.deleteWishDataOf
import com.depromeet.piki.support.itemVersion
import com.depromeet.piki.support.linkItem
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.User
import com.depromeet.piki.user.repository.UserJpaRepository
import com.depromeet.piki.wishlist.domain.Wish
import com.depromeet.piki.wishlist.repository.WishJpaRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// 별도 트랜잭션의 동시 진행이 본질이라 @Transactional 없이 새 UUID 로 격리하고 finally 에서 정리한다
class WishRefreshConcurrencyIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var webApplicationContext: WebApplicationContext

    @Autowired private lateinit var jwtProvider: JwtProvider

    @Autowired private lateinit var userJpaRepository: UserJpaRepository

    @Autowired private lateinit var itemJpaRepository: ItemJpaRepository

    @Autowired private lateinit var itemSnapshotJpaRepository: ItemSnapshotJpaRepository

    @Autowired private lateinit var itemParseOutboxRepository: ItemParseOutboxRepository

    @Autowired private lateinit var itemParsingService: ItemParsingService

    @Autowired private lateinit var wishJpaRepository: WishJpaRepository

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `같은 위시를 동시에 두 번 새로고침해도 행 락으로 직렬화되어 파싱 요청이 하나만 쌓인다`() {
        // 락이 없으면 두 요청이 모두 진행 중 요청 없음을 읽고 각자 아웃박스를 쌓아 추출이 중복된다
        val userId = UUID.randomUUID()
        userJpaRepository.save(
            User(id = userId, nickname = "refresh", profileImage = "https://cdn.example.com/o.jpg", identityType = IdentityType.MEMBER),
        )
        val item = itemJpaRepository.save(linkItem("https://shop.example.com/products/race-refresh"))
        itemSnapshotJpaRepository.save(itemVersion(item.getId(), name = "옛 상품", price = 10_000, createdBy = userId))
        val wish = wishJpaRepository.save(Wish(userId = userId, itemId = item.getId()))

        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val auth = "Bearer ${jwtProvider.generateAccessToken(userId, IdentityType.MEMBER)}"

        try {
            val status200 = AtomicInteger(0)
            val statusOther = AtomicInteger(0)
            val executor = Executors.newFixedThreadPool(2)
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val done = CountDownLatch(2)

            try {
                repeat(2) {
                    executor.submit {
                        ready.countDown()
                        start.await()
                        try {
                            val res =
                                mockMvc
                                    .perform(
                                        post("/api/v1/wishlists/${wish.getId()}/refresh")
                                            .header(HttpHeaders.AUTHORIZATION, auth),
                                    ).andReturn()
                            when (res.response.status) {
                                200 -> status200.incrementAndGet()
                                else -> statusOther.incrementAndGet()
                            }
                        } finally {
                            done.countDown()
                        }
                    }
                }

                assertTrue(ready.await(10, TimeUnit.SECONDS), "작업 스레드가 10초 안에 시작되어야 한다")
                start.countDown()
                assertTrue(done.await(10, TimeUnit.SECONDS), "동시 요청이 10초 안에 완료되어야 한다")
            } finally {
                executor.shutdownNow()
                executor.awaitTermination(5, TimeUnit.SECONDS)
            }

            assertEquals(2, status200.get(), "두 요청 모두 200 이어야 한다(생성 + 멱등)")
            assertEquals(0, statusOther.get())
            val outboxCount =
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM item_parse_outbox WHERE slot_type = ? AND slot_id = ?",
                    Int::class.java,
                    wish.slot.type.name,
                    wish.slot.id,
                )
            assertEquals(1, outboxCount, "락이 없으면 동시 생성으로 2")
        } finally {
            jdbcTemplate.deleteWishDataOf(userId)
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", uuidToBytes(userId))
        }
    }

    @Test
    fun `파싱 성공과 메모 수정이 동시에 와도 행 락으로 직렬화되어 확정된 아이템과 메모가 모두 보존된다`() {
        // 메모 UPDATE 는 전 컬럼을 쓰므로 락 없이 읽으면 그 사이 확정된 item_id 를 null 로 되덮는다(lost update)
        val userId = UUID.randomUUID()
        userJpaRepository.save(
            User(id = userId, nickname = "memo-race", profileImage = "https://cdn.example.com/o.jpg", identityType = IdentityType.MEMBER),
        )
        val url = "https://shop.example.com/products/race-memo-${UUID.randomUUID()}"
        val wish = wishJpaRepository.save(Wish(userId = userId, itemId = null))
        val outbox =
            itemParseOutboxRepository.save(
                ItemParseOutbox.of(wish.slot, ParsePurpose.REGISTER, ParseInput.Link(ProductLink.parse(url))).apply { claim() },
            )

        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val auth = "Bearer ${jwtProvider.generateAccessToken(userId, IdentityType.MEMBER)}"

        try {
            val succeeded = AtomicInteger(0)
            val failed = AtomicInteger(0)
            val executor = Executors.newFixedThreadPool(2)
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val done = CountDownLatch(2)

            val tasks: List<() -> Boolean> =
                listOf(
                    {
                        itemParsingService.markExtracted(
                            outbox.getId(),
                            ProductSnapshot(
                                name = "확정 상품",
                                price = 10_000,
                                currency = "KRW",
                                imageUrl = "https://img.example.com/a.png",
                            ),
                        ) == ExtractionSettlement.Applied(ItemStatus.READY)
                    },
                    {
                        mockMvc
                            .perform(
                                multipart("/api/v1/wishlists/${wish.getId()}")
                                    .param("memo", "동시 메모")
                                    .with {
                                        it.method = "PATCH"
                                        it
                                    }.header(HttpHeaders.AUTHORIZATION, auth),
                            ).andReturn()
                            .response.status == 200
                    },
                )
            // 단언 실패에도 executor 를 종료해야 non-daemon 스레드가 테스트 JVM 을 붙잡지 않는다
            try {
                tasks.forEach { task ->
                    executor.submit {
                        ready.countDown()
                        start.await()
                        try {
                            if (task()) succeeded.incrementAndGet() else failed.incrementAndGet()
                        } catch (e: Exception) {
                            failed.incrementAndGet()
                        } finally {
                            done.countDown()
                        }
                    }
                }

                assertTrue(ready.await(10, TimeUnit.SECONDS), "작업 스레드가 10초 안에 시작되어야 한다")
                start.countDown()
                assertTrue(done.await(10, TimeUnit.SECONDS), "동시 요청이 10초 안에 완료되어야 한다")
            } finally {
                executor.shutdownNow()
                executor.awaitTermination(5, TimeUnit.SECONDS)
            }

            assertEquals(2, succeeded.get())
            assertEquals(0, failed.get())
            val row = jdbcTemplate.queryForMap("SELECT item_id, memo FROM wishes WHERE id = ?", wish.getId())
            assertEquals("동시 메모", row["memo"], "메모가 저장되어야 한다")
            assertNotNull(row["item_id"], "파싱이 확정한 아이템이 메모 갱신의 전 컬럼 UPDATE 에 되덮이면 안 된다")
        } finally {
            jdbcTemplate.deleteWishDataOf(userId)
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", uuidToBytes(userId))
        }
    }
}
