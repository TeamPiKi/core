package com.depromeet.piki.tournament.controller

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.item.repository.ItemJpaRepository
import com.depromeet.piki.item.repository.ItemSnapshotJpaRepository
import com.depromeet.piki.item.service.UniqueConflictRetry
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.deleteItems
import com.depromeet.piki.support.imageItem
import com.depromeet.piki.support.itemVersion
import com.depromeet.piki.support.runningQueries
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.tournament.domain.TournamentItem
import com.depromeet.piki.tournament.repository.TournamentItemJpaRepository
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.User
import com.depromeet.piki.user.repository.UserJpaRepository
import com.depromeet.piki.wishlist.domain.Wish
import com.depromeet.piki.wishlist.repository.WishJpaRepository
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper
import java.time.Duration
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
    @Autowired private lateinit var tournamentItemJpaRepository: TournamentItemJpaRepository
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    // 후보 확정을 SQL 로 재현한다. 담기의 삽입이 그 확정을 기다린 뒤에 커밋해야 담기의 스냅샷이 커밋 전으로 고정된다
    @Test
    fun `위시에서 담기가 같은 상품으로 확정 중인 후보와 겹치면 다시 실행해 409 를 반환한다`() {
        val ownerId = UUID.randomUUID()
        userJpaRepository.save(User(id = ownerId, nickname = "race-conf", profileImage = "https://cdn.example.com/o.jpg", identityType = IdentityType.MEMBER))
        val item = itemJpaRepository.save(imageItem())
        itemSnapshotJpaRepository.save(itemVersion(itemId = item.getId(), createdBy = ownerId))
        wishJpaRepository.save(Wish(userId = ownerId, itemId = item.getId()))
        val mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        val ownerAuth = "Bearer ${jwtProvider.generateAccessToken(ownerId, IdentityType.MEMBER)}"
        val retryLogs = ListAppender<ILoggingEvent>().apply { start() }
        val retryLogger = LoggerFactory.getLogger(UniqueConflictRetry::class.java) as Logger
        retryLogger.addAppender(retryLogs)
        val confirmer = Executors.newSingleThreadExecutor()
        var tournamentId = 0L
        try {
            val createResult =
                mockMvc
                    .perform(
                        post("/api/v1/tournaments")
                            .header(HttpHeaders.AUTHORIZATION, ownerAuth)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"name":"확정동시성토너먼트"}"""),
                    ).andReturn()
            tournamentId = objectMapper.readTree(createResult.response.contentAsString)["data"]["tournamentId"].asLong()
            val confirmingEntry = tournamentItemJpaRepository.save(TournamentItem(tournamentId = tournamentId, userId = UUID.randomUUID(), itemId = null))
            val entryFilled = CountDownLatch(1)
            val confirming =
                confirmer.submit {
                    TransactionTemplate(transactionManager).executeWithoutResult {
                        jdbcTemplate.update("UPDATE tournament_items SET item_id = ? WHERE id = ?", item.getId(), confirmingEntry.getId())
                        entryFilled.countDown()
                        await().atMost(Duration.ofSeconds(5)).until { jdbcTemplate.runningQueries("%insert into tournament_items%") > 0 }
                    }
                }
            assertTrue(entryFilled.await(5, TimeUnit.SECONDS))

            mockMvc
                .perform(
                    post("/api/v1/tournaments/$tournamentId/items/wish")
                        .header(HttpHeaders.AUTHORIZATION, ownerAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mapOf("itemIds" to listOf(item.getId())))),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("TOURNAMENT-009"))
            confirming.get(5, TimeUnit.SECONDS)

            assertTrue(retryLogs.list.any { it.formattedMessage.startsWith("item.confirm.retry tournament=$tournamentId") })
        } finally {
            confirmer.shutdownNow()
            retryLogger.detachAppender(retryLogs)
            if (tournamentId != 0L) {
                jdbcTemplate.update("DELETE FROM tournament_items WHERE tournament_id = ?", tournamentId)
                jdbcTemplate.update("DELETE FROM tournament_users WHERE tournament_id = ?", tournamentId)
                jdbcTemplate.update("DELETE FROM tournaments WHERE id = ?", tournamentId)
            }
            jdbcTemplate.update("DELETE FROM wishes WHERE user_id = ?", uuidToBytes(ownerId))
            jdbcTemplate.deleteItems(listOf(item.getId()))
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", uuidToBytes(ownerId))
        }
    }

    @Test
    fun `위시 아이템 담기를 동시에 두 번 요청하면 행 락으로 직렬화되어 32개 상한을 초과하지 않는다`() {
        val ownerId = UUID.randomUUID()
        userJpaRepository.save(
            // 토너먼트 생성은 회원 전용(#339)이라 owner 는 MEMBER 다. 위시 아이템을 담는 시나리오라 MEMBER 가 원래 자연스럽다.
            User(id = ownerId, nickname = "race-wish", profileImage = "https://cdn.example.com/o.jpg", identityType = IdentityType.MEMBER),
        )

        val mockMvc = MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

        val ownerAuth = "Bearer ${jwtProvider.generateAccessToken(ownerId, IdentityType.MEMBER)}"

        // A 요청(1-20), B 요청(21-40) 합산 40개는 TOURNAMENT_MAX_ITEM_COUNT(32)를 초과해 두 요청이 직렬화되면 하나는 반드시 차단된다.
        val items = itemJpaRepository.saveAll((1..40).map { imageItem() })
        itemSnapshotJpaRepository.saveAll(items.mapIndexed { i, item -> itemVersion(itemId = item.getId(), name = "race-wish-item-${i + 1}") })
        wishJpaRepository.saveAll(items.map { Wish(userId = ownerId, itemId = it.getId()) })

        val itemIds = items.map { it.getId() }

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
            jdbcTemplate.deleteItems(itemIds)
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", uuidToBytes(ownerId))
        }
    }
}
