package com.depromeet.piki.tournament.controller

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.repository.ItemJpaRepository
import com.depromeet.piki.item.repository.ItemSnapshotJpaRepository
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubImageParsingWorker
import com.depromeet.piki.support.StubImageStorage
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
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 이미지 담기는 verifyCanAddItems(행 락 없는 readonly 사전검증)를 통과해도, 정원(32) 최종 판정은 persist 의
// findTournamentByIdForUpdate(FOR UPDATE)가 쥔다. 행 락이 없으면 동시 요청 둘이 각각 existing=27 을 읽어 32 체크를
// 통과해 합산 37개가 들어간다. FOR UPDATE 로 직렬화하면 두 번째 요청이 첫 번째 커밋 후 existing=32 를 보고 32+5>32 로
// 400 처리된다. "정확히 1개 200, 1개 400" 이 그 직렬화의 시그니처다(TournamentWishAddConcurrencyIntegrationTest 와 동결).
//
// 더해, 이미지 경로는 raw 를 persist 전에 S3 에 올리므로 거부된 요청의 raw 가 orphan 으로 남는다 — 서비스가 persist 실패 시
// 즉시 회수하는지(addItemsFromImages 의 deleteRawsQuietly)를 deletedKeys 로 함께 검증한다.
//
// 일반 통합 테스트와 달리 @Transactional 을 쓰지 않는다 — 별도 트랜잭션 동시 진행이 race 시뮬레이션의 본질이다.
// 데이터 격리는 새 UUID 를 쓰고 finally 에서 직접 정리한다.
class TournamentItemImageAddConcurrencyIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var webApplicationContext: WebApplicationContext
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var jwtProvider: JwtProvider
    @Autowired private lateinit var userJpaRepository: UserJpaRepository
    @Autowired private lateinit var itemJpaRepository: ItemJpaRepository
    @Autowired private lateinit var itemSnapshotJpaRepository: ItemSnapshotJpaRepository
    @Autowired private lateinit var tournamentItemJpaRepository: TournamentItemJpaRepository
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var stubImageStorage: StubImageStorage
    @Autowired private lateinit var stubImageParsingWorker: StubImageParsingWorker

    @Test
    fun `이미지 담기를 동시에 두 번 요청하면 FOR UPDATE 로 직렬화되어 32개 상한을 넘지 않고 거부된 요청의 raw 가 회수된다`() {
        // 디스패처(@Scheduled)가 성공 요청의 PENDING raw 를 워커로 회수하면 deletedKeys 단언이 흔들린다 — 워커를 꺼
        // 성공분 raw 는 PENDING 으로 보존하고, 거부분 raw 회수(서비스 cleanup)만 결정적으로 관찰한다.
        // enabled 는 컨텍스트 공유 전역 상태라, try 진입 전 setup 이 실패해 끈 채 새면 다른 테스트가 연쇄 실패한다.
        // 끄기는 try 안으로 미루고 원래 값을 보관해, finally 가 항상 원복하도록 한다.
        val previousWorkerEnabled = stubImageParsingWorker.enabled

        val ownerId = UUID.randomUUID()
        userJpaRepository.save(
            // 토너먼트 생성은 회원 전용(#339)이라 owner 는 MEMBER 다. 이 테스트의 관심사는 동시 추가 경합이지
            // 게스트 권한이 아니므로, 계약을 맞추기만 하고 나머지 시나리오는 그대로 둔다.
            User(id = ownerId, nickname = "race-image", profileImage = "https://cdn.example.com/o.jpg", identityType = IdentityType.MEMBER),
        )

        val mockMvc = MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

        val ownerAuth = "Bearer ${jwtProvider.generateAccessToken(ownerId, IdentityType.MEMBER)}"

        // 이 테스트가 새로 만드는 item/snapshot 의 하한 — finally 에서 이보다 큰 id 만 지워 추가분(사전 27 + 성공 5)까지 정리한다.
        val maxItemIdBefore = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM items", Long::class.java) ?: 0L
        // deletedKeys 는 컨텍스트 캐싱으로 모든 통합 테스트가 공유하는 stub 누적 상태다 — 앞선 테스트의 raw 삭제가 섞이므로
        // 절대값이 아니라 이 테스트가 만든 증가분(delta)만 본다.
        val rawDeletedBefore = stubImageStorage.deletedKeys.count { it.startsWith("items/raw/") }

        var tournamentId = 0L
        try {
            stubImageParsingWorker.enabled = false
            // 토너먼트 생성 — TournamentUser(owner) 도 함께 생성된다(verifyCanAddItems 의 참여자 검증 통과).
            val createResult = mockMvc.perform(
                post("/api/v1/tournaments")
                    .header(HttpHeaders.AUTHORIZATION, ownerAuth)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"이미지동시성토너먼트"}"""),
            ).andReturn()
            tournamentId = objectMapper.readTree(createResult.response.contentAsString)["data"]["tournamentId"].asLong()

            // 출전 아이템 27개를 미리 채운다 — 각 요청(5장)은 27+5=32 로 단독 통과하지만, 둘이 합쳐 27+10=37>32 라
            // 직렬화되면 반드시 하나가 거부된다. 정원 카운트는 tournament_items 행 수만 보므로 snapshot 상태는 무관(READY 로 둔다).
            val items = itemJpaRepository.saveAll((1..27).map { Item() })
            val snapshots = itemSnapshotJpaRepository.saveAll(
                items.mapIndexed { i, item ->
                    ItemSnapshot(
                        itemId = item.getId(),
                        name = "race-image-item-${i + 1}",
                        price = 10_000,
                        currency = "KRW",
                        status = ItemStatus.READY,
                        extractedAt = LocalDateTime.now(),
                    )
                },
            )
            tournamentItemJpaRepository.saveAll(
                snapshots.map { TournamentItem(tournamentId = tournamentId, userId = ownerId, snapshotId = it.getId()) },
            )

            val status200 = AtomicInteger(0)
            val status400 = AtomicInteger(0)
            // 예상 밖 응답은 삼키지 않고 증거(status+body)로 보존한다 — 과거 이 테스트가 간헐 실패했을 때
            // else 없는 when 이 제3 상태의 정체를 삼켜 원인 추적이 불가능했다. 작업 큐 claim 스캔이 대기 에지로
            // 끼는 InnoDB 교착이 실측됐고 SKIP LOCKED 로 제거됐다. 만에 하나 재발하면 이 증거가 정체를 밝힌다.
            val unexpectedResponses = CopyOnWriteArrayList<String>()
            val executor = Executors.newFixedThreadPool(2)
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val done = CountDownLatch(2)

            val futures = (0 until 2).map { req ->
                executor.submit {
                    ready.countDown()
                    start.await()
                    try {
                        val builder = multipart("/api/v1/tournaments/$tournamentId/items/images")
                        repeat(5) { i ->
                            builder.file(MockMultipartFile("images", "req$req-$i.jpg", "image/jpeg", ByteArray(10) { 1 }))
                        }
                        val res = mockMvc.perform(builder.header(HttpHeaders.AUTHORIZATION, ownerAuth)).andReturn()
                        when (res.response.status) {
                            200, 201 -> status200.incrementAndGet()
                            400 -> status400.incrementAndGet()
                            else -> unexpectedResponses.add(
                                "status=${res.response.status} body=${res.response.contentAsString}",
                            )
                        }
                    } finally {
                        done.countDown()
                    }
                }
            }

            // executor 종료는 finally 가 보장하고(단언 실패로 새는 스레드 방지), 요청 스레드 내부 예외는 get() 으로 본문에 전파한다(삼키면 거짓 통과).
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS), "두 요청 스레드가 출발 대기에 들어가야 한다")
                start.countDown()
                assertTrue(done.await(15, TimeUnit.SECONDS), "동시 요청이 15초 안에 완료되어야 한다")
                futures.forEach { it.get(1, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }

            assertTrue(unexpectedResponses.isEmpty(), "200/400 외 응답이 있었다 — 증거: $unexpectedResponses")
            assertEquals(1, status200.get(), "정확히 하나만 성공이어야 한다 (5장 담기 성공)")
            assertEquals(1, status400.get(), "나머지 하나는 락 대기 후 32개 초과로 400 이어야 한다")

            // 두 요청 모두 persist 전에 5장씩 raw 를 올리지만(합 10장), 거부된 요청의 5장만 서비스가 즉시 회수해야 한다.
            // 성공분 5장은 PENDING item 의 입력이라 보존된다(워커를 꺼 둬 회수되지 않음). lifecycle 백업이 아닌 즉시 회수를 단언.
            val rawDeleted = stubImageStorage.deletedKeys.count { it.startsWith("items/raw/") } - rawDeletedBefore
            assertEquals(5, rawDeleted, "거부된 요청이 올린 raw 5장이 즉시 회수되어야 한다")
        } finally {
            stubImageParsingWorker.enabled = previousWorkerEnabled
            // 워커를 꺼 둔 탓에 성공분 raw 는 stub 에 orphan 으로 남는다(정상 흐름이면 워커가 회수). 동시성 테스트는 자기가 만든 것을
            // 직접 정리하므로, DB 행을 지우기 전에 이 테스트가 올린 raw 도 stub 에서 회수한다(공유 stub 누적 방지).
            jdbcTemplate.queryForList(
                "SELECT source_image_key FROM items WHERE id > ? AND source_image_key IS NOT NULL",
                String::class.java,
                maxItemIdBefore,
            ).filterNotNull().forEach { key -> runCatching { stubImageStorage.delete(key) } }
            // @Transactional 자동 롤백이 없으므로 직접 지운다. 추가된 item/snapshot 은 id 하한으로 일괄 정리한다.
            if (tournamentId != 0L) {
                jdbcTemplate.update("DELETE FROM tournament_items WHERE tournament_id = ?", tournamentId)
                jdbcTemplate.update("DELETE FROM tournament_users WHERE tournament_id = ?", tournamentId)
                jdbcTemplate.update("DELETE FROM tournaments WHERE id = ?", tournamentId)
            }
            jdbcTemplate.update("DELETE FROM item_snapshots WHERE item_id > ?", maxItemIdBefore)
            jdbcTemplate.update("DELETE FROM items WHERE id > ?", maxItemIdBefore)
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", uuidToBytes(ownerId))
        }
    }
}
