package com.depromeet.piki.tournament.controller

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.repository.ItemJpaRepository
import com.depromeet.piki.item.repository.ItemSnapshotJpaRepository
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.tournament.domain.TournamentItem
import com.depromeet.piki.tournament.repository.TournamentItemJpaRepository
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.User
import com.depromeet.piki.user.repository.UserJpaRepository
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

// start 는 PENDING→IN_PROGRESS 상태 전이와 TournamentStarted 발행을 함께 한다. 락 없이 읽으면 동시 두 요청이
// 모두 PENDING 검증을 통과해 시작 알림을 중복 발행(참가자에게 시작 알림 2번 도달)할 수 있어, forUpdate 행 락으로
// 직렬화한다. "정확히 1개만 200, 나머지는 409" 가 그 락 동작의 시그니처다 — 200 이 1개 = start 성공(곧 발행) 1회.
//
// 일반 통합 테스트와 달리 @Transactional 을 쓰지 않는다 — 별도 트랜잭션 동시 진행이 race 시뮬레이션의 본질이다.
// 데이터 격리는 새 UUID 를 쓰고 finally 에서 직접 정리한다.
class TournamentStartConcurrencyIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var webApplicationContext: WebApplicationContext
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var jwtProvider: JwtProvider
    @Autowired private lateinit var userJpaRepository: UserJpaRepository
    @Autowired private lateinit var itemJpaRepository: ItemJpaRepository
    @Autowired private lateinit var itemSnapshotJpaRepository: ItemSnapshotJpaRepository
    @Autowired private lateinit var tournamentItemJpaRepository: TournamentItemJpaRepository
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `같은 주최자가 start 를 동시에 두 번 요청하면 하나만 200 나머지는 409 로 처리된다`() {
        val ownerId = UUID.randomUUID()
        userJpaRepository.save(
            // 토너먼트 생성은 회원 전용(#339)이라 owner 는 MEMBER 다. 이 테스트의 관심사는 동시 start 경합이다.
            User(id = ownerId, nickname = "race-start", profileImage = "https://cdn.example.com/o.jpg", identityType = IdentityType.MEMBER),
        )

        val mockMvc = MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

        val ownerAuth = "Bearer ${jwtProvider.generateAccessToken(ownerId, IdentityType.MEMBER)}"

        // PENDING 토너먼트 + READY 아이템 2개 준비 (단일 스레드, 직렬). @Transactional 이 없어 실제 커밋되므로,
        // 만든 item/snapshot id 를 끝의 정리에서 쓰기 위해 메서드 스코프로 잡는다(다른 테스트의 item 카운트 오염 방지).
        val createResult = mockMvc.perform(
            post("/api/v1/tournaments")
                .header(HttpHeaders.AUTHORIZATION, ownerAuth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"start동시성토너먼트"}"""),
        ).andReturn()
        val tournamentId = objectMapper.readTree(createResult.response.contentAsString)["data"]["tournamentId"].asLong()

        // tournament_item 은 고정 snapshot 을 참조한다(4a). item 은 정체성(link)만 들고, 표시값·상태는 READY snapshot 이 보유한다.
        val item1 = itemJpaRepository.save(Item())
        val item2 = itemJpaRepository.save(Item())
        val snapshot1 = itemSnapshotJpaRepository.save(
            ItemSnapshot(itemId = item1.getId(), name = "race-item1", price = 10_000, currency = "KRW", status = ItemStatus.READY, extractedAt = LocalDateTime.now()),
        )
        val snapshot2 = itemSnapshotJpaRepository.save(
            ItemSnapshot(itemId = item2.getId(), name = "race-item2", price = 20_000, currency = "KRW", status = ItemStatus.READY, extractedAt = LocalDateTime.now()),
        )
        tournamentItemJpaRepository.save(TournamentItem(tournamentId = tournamentId, userId = ownerId, snapshotId = snapshot1.getId()))
        tournamentItemJpaRepository.save(TournamentItem(tournamentId = tournamentId, userId = ownerId, snapshotId = snapshot2.getId()))

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

        // 정리 — @Transactional 자동 롤백이 없으므로 만든 행을 전부 직접 지운다. item/snapshot 까지 지워야
        // 다른 테스트의 전체 item 카운트가 오염되지 않는다.
        jdbcTemplate.update("DELETE FROM tournament_items WHERE tournament_id = ?", tournamentId)
        jdbcTemplate.update("DELETE FROM tournament_users WHERE tournament_id = ?", tournamentId)
        jdbcTemplate.update("DELETE FROM tournaments WHERE id = ?", tournamentId)
        jdbcTemplate.update("DELETE FROM item_snapshots WHERE id IN (?, ?)", snapshot1.getId(), snapshot2.getId())
        jdbcTemplate.update("DELETE FROM items WHERE id IN (?, ?)", item1.getId(), item2.getId())
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", uuidToBytes(ownerId))
    }
}
