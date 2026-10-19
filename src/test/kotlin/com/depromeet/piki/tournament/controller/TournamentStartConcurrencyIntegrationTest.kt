package com.depromeet.piki.tournament.controller

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.item.repository.ItemJpaRepository
import com.depromeet.piki.item.repository.ItemSnapshotJpaRepository
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
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
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

// start 는 PENDING→IN_PROGRESS 상태 전이와 TournamentStarted 발행을 함께 한다. 락 없이 읽으면 동시 두 요청이
// 모두 PENDING 검증을 통과해 시작 알림을 중복 발행(참가자에게 시작 알림 2번 도달)할 수 있어, forUpdate 행 락으로
// 직렬화한다. 200 이 정확히 1개면 start 성공(곧 발행)도 1회다.
class TournamentStartConcurrencyIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var webApplicationContext: WebApplicationContext
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var jwtProvider: JwtProvider
    @Autowired private lateinit var userJpaRepository: UserJpaRepository
    @Autowired private lateinit var itemJpaRepository: ItemJpaRepository
    @Autowired private lateinit var itemSnapshotJpaRepository: ItemSnapshotJpaRepository
    @Autowired private lateinit var tournamentItemJpaRepository: TournamentItemJpaRepository
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `같은 주최자가 start 를 동시에 두 번 요청하면 하나만 200 나머지는 409 로 처리된다`() {
        val ownerId = UUID.randomUUID()
        userJpaRepository.save(
            // 토너먼트 생성은 회원 전용(#339)
            User(id = ownerId, nickname = "race-start", profileImage = "https://cdn.example.com/o.jpg", identityType = IdentityType.MEMBER),
        )

        val mockMvc = MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

        val ownerAuth = "Bearer ${jwtProvider.generateAccessToken(ownerId, IdentityType.MEMBER)}"

        val createResult = mockMvc.perform(
            post("/api/v1/tournaments")
                .header(HttpHeaders.AUTHORIZATION, ownerAuth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"start동시성토너먼트"}"""),
        ).andReturn()
        val tournamentId = objectMapper.readTree(createResult.response.contentAsString)["data"]["tournamentId"].asLong()

        val item1 = itemJpaRepository.save(imageItem())
        val item2 = itemJpaRepository.save(imageItem())
        itemSnapshotJpaRepository.save(itemVersion(itemId = item1.getId(), name = "race-item1", price = 10_000))
        itemSnapshotJpaRepository.save(itemVersion(itemId = item2.getId(), name = "race-item2", price = 20_000))
        tournamentItemJpaRepository.save(TournamentItem(tournamentId = tournamentId, userId = ownerId, itemId = item1.getId()))
        tournamentItemJpaRepository.save(TournamentItem(tournamentId = tournamentId, userId = ownerId, itemId = item2.getId()))

        val status200 = AtomicInteger(0)
        val status409 = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)

        repeat(2) {
            executor.submit {
                ready.countDown()
                start.await()
                try {
                    val res = mockMvc.perform(
                        post("/api/v1/tournaments/$tournamentId/start")
                            .header(HttpHeaders.AUTHORIZATION, ownerAuth),
                    ).andReturn()
                    when (res.response.status) {
                        200 -> status200.incrementAndGet()
                        409 -> status409.incrementAndGet()
                    }
                } finally {
                    done.countDown()
                }
            }
        }

        ready.await()
        start.countDown()
        done.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        assertEquals(1, status200.get(), "정확히 하나만 200 이어야 한다 (start 성공 1회 = 발행 1회)")
        assertEquals(1, status409.get(), "나머지 하나는 락 대기 후 PENDING 아님으로 409 여야 한다")

        jdbcTemplate.update("DELETE FROM tournament_items WHERE tournament_id = ?", tournamentId)
        jdbcTemplate.update("DELETE FROM tournament_users WHERE tournament_id = ?", tournamentId)
        jdbcTemplate.update("DELETE FROM tournaments WHERE id = ?", tournamentId)
        jdbcTemplate.deleteItems(listOf(item1.getId(), item2.getId()))
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", uuidToBytes(ownerId))
    }

    // 시작은 아이템 잠금을 기다리기 전에 첫 일반 읽기를 해 스냅샷이 새 버전의 커밋 전으로 고정된다
    @Test
    fun `아이템 잠금을 기다리는 동안 커밋된 새 버전의 가격으로 시작한다`() {
        val ownerId = UUID.randomUUID()
        userJpaRepository.save(
            User(id = ownerId, nickname = "race-lock", profileImage = "https://cdn.example.com/o.jpg", identityType = IdentityType.MEMBER),
        )
        val item1 = itemJpaRepository.save(imageItem())
        val item2 = itemJpaRepository.save(imageItem())
        val versionWriter = Executors.newSingleThreadExecutor()
        var tournamentId: Long? = null
        try {
            val mockMvc = MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
            val ownerAuth = "Bearer ${jwtProvider.generateAccessToken(ownerId, IdentityType.MEMBER)}"
            val createResult = mockMvc.perform(
                post("/api/v1/tournaments")
                    .header(HttpHeaders.AUTHORIZATION, ownerAuth)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"잠금읽기토너먼트"}"""),
            ).andReturn()
            val createdId = objectMapper.readTree(createResult.response.contentAsString)["data"]["tournamentId"].asLong()
            tournamentId = createdId
            itemSnapshotJpaRepository.save(itemVersion(itemId = item1.getId(), name = "lock-item1", price = 10_000))
            itemSnapshotJpaRepository.save(itemVersion(itemId = item2.getId(), name = "lock-item2", price = 20_000))
            tournamentItemJpaRepository.save(TournamentItem(tournamentId = createdId, userId = ownerId, itemId = item1.getId()))
            tournamentItemJpaRepository.save(TournamentItem(tournamentId = createdId, userId = ownerId, itemId = item2.getId()))

            val versionInserted = CountDownLatch(1)
            val writing =
                versionWriter.submit {
                    TransactionTemplate(transactionManager).executeWithoutResult {
                        jdbcTemplate.queryForObject("SELECT id FROM items WHERE id = ? FOR UPDATE", Long::class.java, item1.getId())
                        itemSnapshotJpaRepository.saveAndFlush(itemVersion(itemId = item1.getId(), name = "lock-item1", price = 30_000))
                        versionInserted.countDown()
                        await().atMost(Duration.ofSeconds(5)).until { jdbcTemplate.runningQueries("%from items %for update%") > 0 }
                    }
                }
            assertTrue(versionInserted.await(5, TimeUnit.SECONDS))

            val res = mockMvc.perform(
                post("/api/v1/tournaments/$createdId/start")
                    .header(HttpHeaders.AUTHORIZATION, ownerAuth),
            ).andReturn()
            writing.get(5, TimeUnit.SECONDS)

            assertEquals(200, res.response.status)
            val items = objectMapper.readTree(res.response.contentAsString)["data"]["items"]
            assertEquals(listOf(20_000, 30_000), List(items.size()) { items[it]["price"].asInt() })
        } finally {
            versionWriter.shutdownNow()
            tournamentId?.let {
                jdbcTemplate.update("DELETE FROM tournament_items WHERE tournament_id = ?", it)
                jdbcTemplate.update("DELETE FROM tournament_users WHERE tournament_id = ?", it)
                jdbcTemplate.update("DELETE FROM tournaments WHERE id = ?", it)
            }
            jdbcTemplate.deleteItems(listOf(item1.getId(), item2.getId()))
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", uuidToBytes(ownerId))
        }
    }
}
