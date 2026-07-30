package com.depromeet.piki.tournament.controller

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.item.repository.ItemSnapshotJpaRepository
import com.depromeet.piki.item.service.ItemParsingService
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.tournament.repository.TournamentHistoryJpaRepository
import com.depromeet.piki.tournament.repository.TournamentItemJpaRepository
import com.depromeet.piki.tournament.service.TournamentErrorCode
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.wishlist.service.WishPersistenceService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

// 매치 로직 백엔드 이관(#683)의 계약 검증. 브래킷은 서버가 파생하고 클라는 그리기만 하므로,
// "GET 이 내려준 currentMatch 를 POST 에 그대로 되돌려주면 통과한다" 가 이 엔드포인트 쌍의 핵심 계약이다.
@Transactional
class TournamentMatchIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var webApplicationContext: WebApplicationContext

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var tournamentItemJpaRepository: TournamentItemJpaRepository

    @Autowired private lateinit var tournamentHistoryJpaRepository: TournamentHistoryJpaRepository

    @Autowired private lateinit var itemSnapshotJpaRepository: ItemSnapshotJpaRepository

    @Autowired private lateinit var itemParsingService: ItemParsingService

    @Autowired private lateinit var wishPersistenceService: WishPersistenceService

    @Autowired private lateinit var jwtProvider: JwtProvider

    private val userId: UUID = UUID.fromString("11111111-2222-3333-4444-555555555555")

    @Test
    fun `GET tournaments-id 는 서버가 브래킷에서 파생한 currentMatch 를 아이템 정보까지 담아 내려준다`() {
        val mockMvc = buildMockMvc()
        // 5명은 2의 거듭제곱이 아니라 정규화 대상이다 — 1매치 + 부전승 3 으로 16강 아닌 4강으로 수렴한다.
        val tournamentId = startTournament(mockMvc, itemCount = 5)

        mockMvc
            .perform(
                get("/api/v1/tournaments/$tournamentId")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.code").doesNotExist())
            .andExpect(jsonPath("$.detail").value("완료했어요."))
            .andExpect(jsonPath("$.data.inProgress.currentRound").value(5))
            .andExpect(jsonPath("$.data.inProgress.currentMatch.first.tournamentItemId").isNumber)
            .andExpect(jsonPath("$.data.inProgress.currentMatch.first.name").isString)
            .andExpect(jsonPath("$.data.inProgress.currentMatch.first.price").isNumber)
            .andExpect(jsonPath("$.data.inProgress.currentMatch.second.tournamentItemId").isNumber)
            .andExpect(jsonPath("$.data.inProgress.currentMatch.second.name").isString)

        val match = currentMatchOf(mockMvc, tournamentId)
        assertNotEquals(match.first, match.second, "같은 아이템이 자기 자신과 붙었다")
    }

    @Test
    fun `5명으로 시작하면 첫 라운드 1매치 후 currentRound 가 4 로 정규화된다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = startTournament(mockMvc, itemCount = 5)
        val match = currentMatchOf(mockMvc, tournamentId)

        // 5명의 첫 라운드는 매치가 1개뿐이라 이 매치를 치르면 라운드가 끝난다 → nextMatch=null
        mockMvc
            .perform(recordMatch(tournamentId, match.first, match.second, winner = match.first, round = 5))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").doesNotExist())
            .andExpect(jsonPath("$.data.nextMatch").doesNotExist())
            .andExpect(jsonPath("$.data.completed").doesNotExist())

        // 정규화 전 로직은 절반씩 깎아 "3강" 이 됐다. 정규화 후에는 승자 1 + 부전승 3 = 4강이다.
        mockMvc
            .perform(
                get("/api/v1/tournaments/$tournamentId")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.inProgress.currentRound").value(4))
    }

    @Test
    fun `서버 브래킷에 없는 조합을 보내면 400 과 TOURNAMENT-034 를 반환한다`() {
        val mockMvc = buildMockMvc()
        // 4명은 가격 오름차순 인접 페어 (items[0],items[1]) · (items[2],items[3]) 로 고정된다.
        val tournamentId = startTournament(mockMvc, itemCount = 4)
        val items = tournamentItemIdsOf(tournamentId)

        // 최저가와 최고가를 붙인 조합은 인접이 아니라 브래킷에 존재할 수 없다.
        mockMvc
            .perform(recordMatch(tournamentId, items[0], items[3], winner = items[0]))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value(TournamentErrorCode.INVALID_MATCH_PAIR.code))
            .andExpect(jsonPath("$.detail").value(TournamentErrorCode.INVALID_MATCH_PAIR.message))
            .andExpect(jsonPath("$.data").doesNotExist())
    }

    @Test
    fun `같은 조합에 같은 승자를 다시 보내면 멱등 성공이고 기록이 늘지 않는다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = startTournament(mockMvc, itemCount = 4)
        val items = tournamentItemIdsOf(tournamentId)

        mockMvc
            .perform(recordMatch(tournamentId, items[0], items[1], winner = items[0]))
            .andExpect(status().isOk)
        val afterFirst = historyCountOf(tournamentId)

        // 재전송·뒤로가기로 같은 요청이 다시 오는 경우. 패자가 이미 탈락 집합에 있어도 409 가 아니라 멱등 성공이어야 한다.
        mockMvc
            .perform(recordMatch(tournamentId, items[0], items[1], winner = items[0]))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").doesNotExist())
            // 4명의 첫 라운드는 2매치라, 재시도 응답도 남은 매치를 nextMatch 로 다시 알려준다.
            .andExpect(jsonPath("$.data.nextMatch.first.tournamentItemId").isNumber)

        assertEquals(afterFirst, historyCountOf(tournamentId), "멱등 재시도가 기록을 중복 적재했다")
    }

    @Test
    fun `같은 조합에 다른 승자를 보내면 409 와 TOURNAMENT-035 를 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = startTournament(mockMvc, itemCount = 4)
        val items = tournamentItemIdsOf(tournamentId)

        mockMvc
            .perform(recordMatch(tournamentId, items[0], items[1], winner = items[0]))
            .andExpect(status().isOk)

        mockMvc
            .perform(recordMatch(tournamentId, items[0], items[1], winner = items[1]))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value(TournamentErrorCode.MATCH_ALREADY_RECORDED.code))
            .andExpect(jsonPath("$.detail").value(TournamentErrorCode.MATCH_ALREADY_RECORDED.message))
            .andExpect(jsonPath("$.data").doesNotExist())
    }

    @Test
    fun `first 와 second 를 뒤집어 보내도 같은 매치로 인정해 200 을 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = startTournament(mockMvc, itemCount = 4)
        val items = tournamentItemIdsOf(tournamentId)

        // 조합이 브래킷의 본질이고 좌/우는 표시 순서일 뿐이라, 뒤집혀 와도 통과한다.
        mockMvc
            .perform(recordMatch(tournamentId, items[1], items[0], winner = items[1]))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").doesNotExist())

        assertEquals(1, historyCountOf(tournamentId))
    }

    @Test
    fun `라운드 내 매치 진행 순서를 바꿔 보내도 둘 다 200 을 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = startTournament(mockMvc, itemCount = 4)
        val items = tournamentItemIdsOf(tournamentId)

        // 서버가 지정한 진행 순서와 무관하게 두 번째 페어를 먼저 보낸다.
        // 라운드 내 매치는 서로 독립이라 순서는 검증하지 않는다 — 강제하면 열린 탭·뒤로가기에서 오탐 400 만 는다.
        mockMvc
            .perform(recordMatch(tournamentId, items[2], items[3], winner = items[2]))
            .andExpect(status().isOk)
        mockMvc
            .perform(recordMatch(tournamentId, items[0], items[1], winner = items[0]))
            .andExpect(status().isOk)
            // 두 매치를 다 치러 첫 라운드가 끝났다 → 클라는 GET 으로 다음 라운드를 받는다.
            .andExpect(jsonPath("$.data.nextMatch").doesNotExist())

        assertEquals(2, historyCountOf(tournamentId))
        mockMvc
            .perform(
                get("/api/v1/tournaments/$tournamentId")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(jsonPath("$.data.inProgress.currentRound").value(2))
    }

    @Test
    fun `결승 매치를 기록하면 nextMatch 는 null 이고 completed 에 순위가 담긴다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = startTournament(mockMvc, itemCount = 2)
        val items = tournamentItemIdsOf(tournamentId)

        mockMvc
            .perform(recordMatch(tournamentId, items[0], items[1], winner = items[0], round = 2))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").doesNotExist())
            .andExpect(jsonPath("$.detail").value("완료했어요."))
            .andExpect(jsonPath("$.data.nextMatch").doesNotExist())
            .andExpect(jsonPath("$.data.completed.result[0].rank").value(1))
            .andExpect(jsonPath("$.data.completed.result[0].tournamentItemId").value(items[0]))
            .andExpect(jsonPath("$.data.completed.result[1].rank").value(2))
            .andExpect(jsonPath("$.data.completed.result[1].tournamentItemId").value(items[1]))
            .andExpect(jsonPath("$.data.completed.hasGroupResult").value(false))
    }

    @Test
    fun `결승을 재전송하면 COMPLETED 여도 409 가 아니라 같은 순위 결과를 다시 받는다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = startTournament(mockMvc, itemCount = 2)
        val items = tournamentItemIdsOf(tournamentId)

        mockMvc
            .perform(recordMatch(tournamentId, items[0], items[1], winner = items[0], round = 2))
            .andExpect(status().isOk)
        val afterFirst = historyCountOf(tournamentId)

        // 결승 응답을 못 받고 재전송하는 경우가 가장 흔한 재시도다. 결승 기록으로 토너먼트가 COMPLETED 로
        // 바뀌므로, 진행 중 검사가 멱등 판정보다 앞에 있으면 이 케이스만 멱등에서 빠져 409 가 났다.
        mockMvc
            .perform(recordMatch(tournamentId, items[0], items[1], winner = items[0], round = 2))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").doesNotExist())
            .andExpect(jsonPath("$.data.nextMatch").doesNotExist())
            .andExpect(jsonPath("$.data.completed.result[0].rank").value(1))
            .andExpect(jsonPath("$.data.completed.result[0].tournamentItemId").value(items[0]))
            .andExpect(jsonPath("$.data.completed.result[1].tournamentItemId").value(items[1]))

        assertEquals(afterFirst, historyCountOf(tournamentId), "결승 재전송이 기록을 중복 적재했다")
    }

    @Test
    fun `완료된 토너먼트의 결승을 다른 승자로 재전송하면 409 TOURNAMENT-035 를 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = startTournament(mockMvc, itemCount = 2)
        val items = tournamentItemIdsOf(tournamentId)

        mockMvc
            .perform(recordMatch(tournamentId, items[0], items[1], winner = items[0], round = 2))
            .andExpect(status().isOk)

        // 완료 후에도 결과 뒤집기는 멱등이 아니다 - 진행 중 검사(409 TOURNAMENT-006)보다
        // 구체적인 사유인 "이미 기록된 대결" 로 답한다.
        mockMvc
            .perform(recordMatch(tournamentId, items[0], items[1], winner = items[1], round = 2))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value(TournamentErrorCode.MATCH_ALREADY_RECORDED.code))
            .andExpect(jsonPath("$.data").doesNotExist())
    }

    private data class Match(
        val first: Long,
        val second: Long,
    )

    private fun buildMockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    private fun authHeader(userId: UUID): String = "Bearer ${jwtProvider.generateAccessToken(userId, IdentityType.MEMBER)}"

    // 가격이 서로 다른 itemCount 개 아이템으로 토너먼트를 만들어 시작한다.
    // 가격을 오름차순으로 넣으므로 tournamentItemIdsOf 의 인덱스 순서가 곧 가격 순서다.
    private fun startTournament(
        mockMvc: MockMvc,
        itemCount: Int,
    ): Long {
        val tournamentId = createTournament(mockMvc)
        val itemIds = (1..itemCount).map { saveWishItem(name = "아이템$it", price = it * 10_000) }
        mockMvc.perform(
            post("/api/v1/tournaments/$tournamentId/items/wish")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"itemIds":${itemIds.joinToString(",", "[", "]")}}"""),
        )
        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/start")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
        return tournamentId
    }

    private fun createTournament(mockMvc: MockMvc): Long {
        val result =
            mockMvc
                .perform(
                    post("/api/v1/tournaments")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"매치 테스트 토너먼트"}"""),
                ).andReturn()
        return objectMapper.readTree(result.response.contentAsString)["data"]["tournamentId"].asLong()
    }

    private fun saveWishItem(
        name: String,
        price: Int,
    ): Long {
        val result = wishPersistenceService.persistPendingImages(userId, listOf("items/raw/${UUID.randomUUID()}.png")).first()
        itemSnapshotJpaRepository.findById(result.snapshot.getId()).get().markProcessing()
        itemParsingService.markReady(
            result.snapshot.getId(),
            ProductSnapshot(name = name, currentPrice = price, currency = "KRW", imageUrl = "https://img.example.com/a.png"),
            expectedAttempt = 0,
        )
        return result.item.getId()
    }

    private fun tournamentItemIdsOf(tournamentId: Long): List<Long> =
        tournamentItemJpaRepository
            .findAllByTournamentIdAndNotDeleted(tournamentId)
            .map { it.getId() }

    // 서버가 지금 치르라고 지정한 매치. 클라이언트가 하는 일과 정확히 같다 — 조회해서 그대로 되돌려준다.
    private fun currentMatchOf(
        mockMvc: MockMvc,
        tournamentId: Long,
    ): Match {
        val result =
            mockMvc
                .perform(
                    get("/api/v1/tournaments/$tournamentId")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
                ).andReturn()
        val match = objectMapper.readTree(result.response.contentAsString)["data"]["inProgress"]["currentMatch"]
        assertTrue(match.isObject, "currentMatch 가 내려오지 않았다")
        return Match(
            first = match["first"]["tournamentItemId"].asLong(),
            second = match["second"]["tournamentItemId"].asLong(),
        )
    }

    private fun historyCountOf(tournamentId: Long): Int =
        tournamentHistoryJpaRepository
            .findAllByTournamentIdInAndDeletedAtIsNull(listOf(tournamentId))
            .size

    private fun recordMatch(
        tournamentId: Long,
        first: Long,
        second: Long,
        winner: Long,
        round: Int = 4,
    ) = post("/api/v1/tournaments/$tournamentId/matches")
        .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            """{"currentRound":$round,"firstTournamentItemId":$first,"secondTournamentItemId":$second,"selectedTournamentItemId":$winner}""",
        )
}
