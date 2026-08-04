package com.depromeet.piki.tournament.controller

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.repository.ItemJpaRepository
import com.depromeet.piki.item.repository.ItemSnapshotJpaRepository
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.User
import com.depromeet.piki.user.repository.UserJpaRepository
import com.depromeet.piki.wishlist.domain.Wish
import com.depromeet.piki.wishlist.repository.WishJpaRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 위시 아이템 담기가 행 락(findTournamentByIdForUpdate) 없이 조회하면, 동시 요청 두 개가 각각
// existingCount=0 을 읽어 상한(32) 체크를 통과해 합산 40개가 들어간다.
// findTournamentByIdForUpdate 로 직렬화하면 두 번째 요청이 첫 번째 커밋 후 existingCount=20 을
// 보고 20+20=40>32 로 400 처리된다. "정확히 1개 200, 1개 400" 이 그 직렬화의 시그니처다.
//
// 일반 통합 테스트와 달리 @Transactional 을 쓰지 않는다 — 별도 트랜잭션 동시 진행이 race 시뮬레이션의 본질이다.
// 데이터 격리는 새 UUID 를 쓰고 finally 에서 직접 정리한다.
class TournamentWishAddConcurrencyIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var webApplicationContext: WebApplicationContext
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var jwtProvider: JwtProvider
    @Autowired private lateinit var userJpaRepository: UserJpaRepository
    @Autowired private lateinit var itemJpaRepository: ItemJpaRepository
    @Autowired private lateinit var itemSnapshotJpaRepository: ItemSnapshotJpaRepository
    @Autowired private lateinit var wishJpaRepository: WishJpaRepository
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `위시 아이템 담기를 동시에 두 번 요청하면 행 락으로 직렬화되어 32개 상한을 초과하지 않는다`() {
        val ownerId = UUID.randomUUID()
        userJpaRepository.save(
            User(id = ownerId, nickname = "race-wish", profileImage = "https://cdn.example.com/o.jpg", identityType = IdentityType.GUEST),
        )

        val mockMvc = MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

        val ownerAuth = "Bearer ${jwtProvider.generateAccessToken(ownerId, IdentityType.GUEST)}"

        // 아이템 40개 + READY 스냅샷 생성 — A 요청(1-20), B 요청(21-40) 에서 나눠 씀.
        // 합산 40개는 TOURNAMENT_MAX_ITEM_COUNT(32)를 초과해 두 요청이 직렬화되면 하나는 반드시 차단된다.
        val items = itemJpaRepository.saveAll((1..40).map { Item() })
        val snapshots = itemSnapshotJpaRepository.saveAll(
            items.mapIndexed { i, item ->
                ItemSnapshot(
                    itemId = item.getId(),
                    name = "race-wish-item-${i + 1}",
                    price = 10_000,
                    currency = "KRW",
                    status = ItemStatus.READY,
                    extractedAt = LocalDateTime.now(),
                )
            },
        )
        val snapshotIdByItemId = snapshots.associateBy({ it.itemId }, { it.getId() })
        wishJpaRepository.saveAll(
            items.map { item ->
                Wish(
                    userId = ownerId,
                    snapshotId = snapshotIdByItemId.getValue(item.getId()),
                )
            },
        )

        val itemIds = items.map { it.getId() }
        val placeholders = itemIds.joinToString(",") { "?" }

        // assertion 실패 시에도 정리가 보장되도록 try-finally 로 감싼다.
        // 정리 없이 남으면 다음 실행 시 item/snapshot 수가 오염되어 다른 테스트에 영향을 줄 수 있다.
        var tournamentId = 0L
        try {
            // 토너먼트 생성 — TournamentUser(owner) 도 함께 생성된다
            val createResult = mockMvc.perform(
                post("/api/v1/tournaments")
                    .header(HttpHeaders.AUTHORIZATION, ownerAuth)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"위시동시성토너먼트"}"""),
            ).andReturn()
            tournamentId = objectMapper.readTree(createResult.response.contentAsString)["data"]["tournamentId"].asLong()

            val requestBodies = listOf(
                objectMapper.writeValueAsString(mapOf("itemIds" to itemIds.subList(0, 20))),  // A: 아이템 1-20
                objectMapper.writeValueAsString(mapOf("itemIds" to itemIds.subList(20, 40))), // B: 아이템 21-40
            )

            val status200 = AtomicInteger(0)
            val status400 = AtomicInteger(0)
            val executor = Executors.newFixedThreadPool(2)
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val done = CountDownLatch(2)

            for (body in requestBodies) {
                executor.submit {
                    ready.countDown()
                    start.await()
                    try {
                        val res = mockMvc.perform(
                            post("/api/v1/tournaments/$tournamentId/items/wish")
                                .header(HttpHeaders.AUTHORIZATION, ownerAuth)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body),
                        ).andReturn()
                        when (res.response.status) {
                            200 -> status200.incrementAndGet()
                            400 -> status400.incrementAndGet()
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

            assertEquals(1, status200.get(), "정확히 하나만 200 이어야 한다 (20개 담기 성공)")
            assertEquals(1, status400.get(), "나머지 하나는 락 대기 후 32개 초과로 400 이어야 한다")
        } finally {
            // @Transactional 자동 롤백 없으므로 직접 지운다
            if (tournamentId != 0L) {
                jdbcTemplate.update("DELETE FROM tournament_items WHERE tournament_id = ?", tournamentId)
                jdbcTemplate.update("DELETE FROM tournament_users WHERE tournament_id = ?", tournamentId)
                jdbcTemplate.update("DELETE FROM tournaments WHERE id = ?", tournamentId)
            }
            jdbcTemplate.update("DELETE FROM wishes WHERE user_id = ?", uuidToBytes(ownerId))
            jdbcTemplate.update("DELETE FROM item_snapshots WHERE item_id IN ($placeholders)", *itemIds.toTypedArray())
            jdbcTemplate.update("DELETE FROM items WHERE id IN ($placeholders)", *itemIds.toTypedArray())
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", uuidToBytes(ownerId))
        }
    }
}
