package com.depromeet.piki.wishlist.controller

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.repository.ItemJpaRepository
import com.depromeet.piki.item.repository.ItemSnapshotJpaRepository
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubItemParsingWorker
import com.depromeet.piki.support.StubProductLinkExtractor
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
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 새로고침이 wish 행 락(findByIdForUpdate) 없이 돌면, 동시 요청 두 개가 각각 활성=READY(진행 중 아님)를 읽어
// 둘 다 새 PENDING 버전을 만든다 → 같은 item 에 진행 중 snapshot 이 2개가 돼 멱등이 깨지고 추출이 중복된다.
// findByIdForUpdate 로 직렬화하면 두 번째 요청은 첫 번째 커밋 후 활성=PENDING(진행 중)을 보고 멱등 no-op 으로 끝난다.
// "새 snapshot 행이 정확히 1개만 늘어 총 2개(옛 READY 1 + 새 PENDING 1)" 가 그 직렬화의 시그니처다.
//
// 일반 통합 테스트와 달리 @Transactional 을 쓰지 않는다 — 별도 트랜잭션 동시 진행이 race 시뮬레이션의 본질이다.
// 데이터 격리는 새 UUID 를 쓰고 finally 에서 직접 정리한다.
class WishRefreshConcurrencyIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var webApplicationContext: WebApplicationContext

    @Autowired private lateinit var jwtProvider: JwtProvider

    @Autowired private lateinit var userJpaRepository: UserJpaRepository

    @Autowired private lateinit var itemJpaRepository: ItemJpaRepository

    @Autowired private lateinit var itemSnapshotJpaRepository: ItemSnapshotJpaRepository

    @Autowired private lateinit var wishJpaRepository: WishJpaRepository

    @Autowired private lateinit var stubProductLinkExtractor: StubProductLinkExtractor

    @Autowired private lateinit var stubItemParsingWorker: StubItemParsingWorker

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `같은 위시를 동시에 두 번 새로고침해도 행 락으로 직렬화되어 새 추출 버전이 하나만 생긴다`() {
        val userId = UUID.randomUUID()
        userJpaRepository.save(
            User(id = userId, nickname = "refresh", profileImage = "https://cdn.example.com/o.jpg", identityType = IdentityType.MEMBER),
        )
        stubProductLinkExtractor.build = {
            ProductSnapshot(link = it, name = "새 상품", price = 20_000, currency = "KRW")
        }

        // 기존 READY 위시 시딩 — 활성 snapshot 1개(옛 READY).
        val item = itemJpaRepository.save(Item(ProductLink.parse("https://shop.example.com/products/race-refresh")))
        val oldSnapshot =
            itemSnapshotJpaRepository.save(
                ItemSnapshot(
                    itemId = item.getId(),
                    name = "옛 상품",
                    price = 10_000,
                    currency = "KRW",
                    status = ItemStatus.READY,
                    extractedAt = LocalDateTime.now(),
                ),
            )
        val wish = wishJpaRepository.save(Wish(userId = userId, snapshotId = oldSnapshot.getId()))
        val itemId = item.getId()

        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val auth = "Bearer ${jwtProvider.generateAccessToken(userId, IdentityType.MEMBER)}"

        try {
            // 디스패처(@Scheduled 1s)가 새 PENDING 을 claim 해 READY 까지 전이시키면, 두 번째 요청이 active=READY 를 보고
            // 세 번째 행을 만들어 count==2 단언이 flake 한다(공유 컨텍스트의 live 워커가 테스트 상태를 가로채는 race).
            // 워커를 무력화해 새 snapshot 이 진행 중(PENDING/PROCESSING)에 머물게 하면, 두 번째 요청은 항상 '진행 중'을 보고
            // 멱등 no-op 한다 — 락의 직렬화만 순수하게 검증하고, teardown-vs-async 충돌·stub 람다 누수도 차단한다.
            stubItemParsingWorker.enabled = false
            val status200 = AtomicInteger(0)
            val statusOther = AtomicInteger(0)
            val executor = Executors.newFixedThreadPool(2)
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val done = CountDownLatch(2)

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

            ready.await()
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS), "동시 요청이 10초 안에 완료되어야 한다")
            executor.shutdown()

            // 첫 요청은 새 PENDING 생성, 둘째는 락 대기 후 진행 중을 보고 멱등 no-op — 둘 다 200.
            assertEquals(2, status200.get(), "두 요청 모두 200 이어야 한다(생성 + 멱등)")
            assertEquals(0, statusOther.get())
            // 핵심 단언: 새 snapshot 행이 정확히 1개만 늘어 총 2개. 락이 없으면 동시 생성으로 3개 이상이 된다.
            val snapshotCount =
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM item_snapshots WHERE item_id = ?",
                    Int::class.java,
                    itemId,
                )
            assertEquals(2, snapshotCount, "옛 READY 1 + 새 PENDING 1 = 2 (락이 없으면 동시 생성으로 3+)")
        } finally {
            stubItemParsingWorker.enabled = true
            jdbcTemplate.update("DELETE FROM wishes WHERE user_id = ?", uuidToBytes(userId))
            jdbcTemplate.update("DELETE FROM item_snapshots WHERE item_id = ?", itemId)
            jdbcTemplate.update("DELETE FROM items WHERE id = ?", itemId)
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", uuidToBytes(userId))
        }
    }

    @Test
    fun `같은 위시에 새로고침과 메모 수정을 동시에 보내도 행 락으로 직렬화되어 스왑과 메모가 모두 보존된다`() {
        // memo 경로가 락 없이 읽으면, UPDATE 가 전 컬럼을 쓰므로(dynamic update 아님) 동시 refresh 가 커밋한
        // snapshot_id 스왑을 memo flush 가 읽어 둔 옛 값으로 되덮는다(lost update). 행 락 직렬화가 그 손실을 막는다 —
        // 어느 순서로 잡히든 "스왑된 포인터 + 저장된 메모" 둘 다 남는 것이 직렬화의 시그니처다.
        val userId = UUID.randomUUID()
        userJpaRepository.save(
            User(id = userId, nickname = "memo-race", profileImage = "https://cdn.example.com/o.jpg", identityType = IdentityType.MEMBER),
        )
        stubProductLinkExtractor.build = {
            ProductSnapshot(link = it, name = "새 상품", price = 20_000, currency = "KRW")
        }

        val item = itemJpaRepository.save(Item(ProductLink.parse("https://shop.example.com/products/race-memo")))
        val oldSnapshot =
            itemSnapshotJpaRepository.save(
                ItemSnapshot(
                    itemId = item.getId(),
                    name = "옛 상품",
                    price = 10_000,
                    currency = "KRW",
                    status = ItemStatus.READY,
                    extractedAt = LocalDateTime.now(),
                ),
            )
        val wish = wishJpaRepository.save(Wish(userId = userId, snapshotId = oldSnapshot.getId()))
        val itemId = item.getId()

        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
        val auth = "Bearer ${jwtProvider.generateAccessToken(userId, IdentityType.MEMBER)}"

        try {
            stubItemParsingWorker.enabled = false
            val status200 = AtomicInteger(0)
            val statusOther = AtomicInteger(0)
            val executor = Executors.newFixedThreadPool(2)
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val done = CountDownLatch(2)

            val requests =
                listOf(
                    { post("/api/v1/wishlists/${wish.getId()}/refresh").header(HttpHeaders.AUTHORIZATION, auth) },
                    {
                        multipart("/api/v1/wishlists/${wish.getId()}")
                            .param("memo", "동시 메모")
                            .with {
                                it.method = "PATCH"
                                it
                            }.header(HttpHeaders.AUTHORIZATION, auth)
                    },
                )
            requests.forEach { request ->
                executor.submit {
                    ready.countDown()
                    start.await()
                    try {
                        val res = mockMvc.perform(request()).andReturn()
                        when (res.response.status) {
                            200 -> status200.incrementAndGet()
                            else -> statusOther.incrementAndGet()
                        }
                    } finally {
                        done.countDown()
                    }
                }
            }

            ready.await()
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS), "동시 요청이 10초 안에 완료되어야 한다")
            executor.shutdown()

            assertEquals(2, status200.get(), "두 요청 모두 200 이어야 한다")
            assertEquals(0, statusOther.get())
            val row = jdbcTemplate.queryForMap("SELECT snapshot_id, memo FROM wishes WHERE id = ?", wish.getId())
            assertEquals("동시 메모", row["memo"], "메모가 저장되어야 한다")
            val finalSnapshotId = (row["snapshot_id"] as Number).toLong()
            assertTrue(
                finalSnapshotId != oldSnapshot.getId(),
                "refresh 가 스왑한 포인터가 memo 갱신의 전 컬럼 UPDATE 에 되덮이면 안 된다",
            )
        } finally {
            stubItemParsingWorker.enabled = true
            jdbcTemplate.update("DELETE FROM wishes WHERE user_id = ?", uuidToBytes(userId))
            jdbcTemplate.update("DELETE FROM item_snapshots WHERE item_id = ?", itemId)
            jdbcTemplate.update("DELETE FROM items WHERE id = ?", itemId)
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", uuidToBytes(userId))
        }
    }
}
