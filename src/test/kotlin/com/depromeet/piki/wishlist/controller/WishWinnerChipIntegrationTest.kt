package com.depromeet.piki.wishlist.controller

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.item.repository.ItemJpaRepository
import com.depromeet.piki.item.repository.ItemSnapshotJpaRepository
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.imageItem
import com.depromeet.piki.support.itemVersion
import com.depromeet.piki.tournament.repository.TournamentItemJpaRepository
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.User
import com.depromeet.piki.user.repository.UserJpaRepository
import com.depromeet.piki.wishlist.domain.Wish
import com.depromeet.piki.wishlist.repository.WishJpaRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 위시 카드의 우승 칩(#1236) 계약. 판정 기준이 "그룹 합산 순위" 가 아니라 "내가 플레이한 결승에서 내가 고른 것" 이라
// 사람마다 답이 다르고, 같은 토너먼트에서도 참여자별로 갈린다. 그 갈림을 여기서 고정한다.
//
// 아이템 2개로 시작하면 첫 매치가 곧 결승(round=2)이라, 우승 판정에 필요한 최소 상태를 한 번의 기록으로 만든다.
@Transactional
class WishWinnerChipIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var webApplicationContext: WebApplicationContext

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var tournamentItemJpaRepository: TournamentItemJpaRepository

    @Autowired private lateinit var itemSnapshotJpaRepository: ItemSnapshotJpaRepository

    @Autowired private lateinit var itemJpaRepository: ItemJpaRepository

    @Autowired private lateinit var wishJpaRepository: WishJpaRepository

    @Autowired private lateinit var userJpaRepository: UserJpaRepository

    @Autowired private lateinit var jwtProvider: JwtProvider

    private val ownerId: UUID = UUID.fromString("a1111111-2222-3333-4444-555555555555")

    private val guestPlayerId: UUID = UUID.fromString("b1111111-2222-3333-4444-555555555555")

    @Test
    fun `내 토너먼트 결승에서 고른 상품은 위시 목록에 우승 칩이 붙는다`() {
        val mockMvc = buildMockMvc()
        saveUser(ownerId, "주최자")
        val winnerItemId = saveWishItem(ownerId, "우승 상품")
        val loserItemId = saveWishItem(ownerId, "탈락 상품")
        val tournamentId = startTournamentWith(mockMvc, listOf(winnerItemId, loserItemId))
        val cards = tournamentItemIdsOf(tournamentId)
        val winnerCard = cardIdOf(cards, winnerItemId)

        playFinal(mockMvc, tournamentId, cards, winner = winnerCard)

        assertEquals(listOf(tournamentId), winnersInList(mockMvc, winnerItemId), "결승에서 고른 상품이 그 토너먼트를 가리키지 않는다")
        assertEquals(emptyList(), winnersInList(mockMvc, loserItemId), "탈락한 상품에 칩이 붙었다")
    }

    @Test
    fun `우승 칩은 위시 상세에서도 목록과 같게 내려온다`() {
        val mockMvc = buildMockMvc()
        saveUser(ownerId, "주최자")
        val winnerItemId = saveWishItem(ownerId, "우승 상품")
        val loserItemId = saveWishItem(ownerId, "탈락 상품")
        val tournamentId = startTournamentWith(mockMvc, listOf(winnerItemId, loserItemId))
        val cards = tournamentItemIdsOf(tournamentId)
        playFinal(mockMvc, tournamentId, cards, winner = cardIdOf(cards, winnerItemId))

        // 목록과 상세가 같은 규칙을 쓰는지 — 두 화면이 어긋나면 칩이 깜빡이는 것처럼 보인다.
        assertEquals(listOf(tournamentId), winnersInDetail(mockMvc, wishIdOf(ownerId, winnerItemId)))
        assertEquals(emptyList(), winnersInDetail(mockMvc, wishIdOf(ownerId, loserItemId)))
    }

    @Test
    fun `한 토너먼트에서 탈락해도 다른 토너먼트에서 우승했으면 칩이 남는다`() {
        val mockMvc = buildMockMvc()
        saveUser(ownerId, "주최자")
        val itemId = saveWishItem(ownerId, "한 번은 우승한 상품")
        val rivalA = saveWishItem(ownerId, "상대 A")
        val rivalB = saveWishItem(ownerId, "상대 B")

        // 1차: 우승
        val first = startTournamentWith(mockMvc, listOf(itemId, rivalA))
        val firstCards = tournamentItemIdsOf(first)
        playFinal(mockMvc, first, firstCards, winner = cardIdOf(firstCards, itemId))

        // 2차: 같은 상품이 탈락
        val second = startTournamentWith(mockMvc, listOf(itemId, rivalB))
        val secondCards = tournamentItemIdsOf(second)
        playFinal(mockMvc, second, secondCards, winner = cardIdOf(secondCards, rivalB))

        assertEquals(listOf(first), winnersInList(mockMvc, itemId), "뒤 토너먼트의 탈락이 앞의 우승을 지웠다")
        assertEquals(listOf(second), winnersInList(mockMvc, rivalB), "2차 우승자에 칩이 없다")
    }

    @Test
    fun `여러 판에서 이긴 상품은 최신 우승이 앞에 오고 한 판만 지우면 그것만 빠진다`() {
        val mockMvc = buildMockMvc()
        saveUser(ownerId, "주최자")
        val itemId = saveWishItem(ownerId, "두 번 우승한 상품")
        val rivalA = saveWishItem(ownerId, "상대 A")
        val rivalB = saveWishItem(ownerId, "상대 B")

        val first = startTournamentWith(mockMvc, listOf(itemId, rivalA))
        val firstCards = tournamentItemIdsOf(first)
        playFinal(mockMvc, first, firstCards, winner = cardIdOf(firstCards, itemId))

        val second = startTournamentWith(mockMvc, listOf(itemId, rivalB))
        val secondCards = tournamentItemIdsOf(second)
        playFinal(mockMvc, second, secondCards, winner = cardIdOf(secondCards, itemId))

        // 나중에 이긴 판이 앞 — 칩을 눌러 한 곳으로 보낼 때 가장 최근 승부로 가게 된다.
        assertEquals(listOf(second, first), winnersInList(mockMvc, itemId), "최신 우승이 앞에 오지 않는다")

        mockMvc
            .perform(
                delete("/api/v1/tournaments/$second")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(ownerId)),
            ).andExpect(status().isOk)

        // 지운 판만 빠지고 나머지 우승은 남는다 — 칩 자체는 그대로다.
        assertEquals(listOf(first), winnersInList(mockMvc, itemId), "지운 판 외의 우승까지 사라졌다")
    }

    @Test
    fun `토너먼트를 삭제하면 그 우승으로 붙었던 칩이 사라진다`() {
        val mockMvc = buildMockMvc()
        saveUser(ownerId, "주최자")
        val winnerItemId = saveWishItem(ownerId, "우승 상품")
        val loserItemId = saveWishItem(ownerId, "탈락 상품")
        val tournamentId = startTournamentWith(mockMvc, listOf(winnerItemId, loserItemId))
        val cards = tournamentItemIdsOf(tournamentId)
        playFinal(mockMvc, tournamentId, cards, winner = cardIdOf(cards, winnerItemId))
        assertTrue(winnersInList(mockMvc, winnerItemId).isNotEmpty(), "삭제 전부터 칩이 없어 이 테스트가 의미를 잃는다")

        mockMvc
            .perform(
                delete("/api/v1/tournaments/$tournamentId")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(ownerId)),
            ).andExpect(status().isOk)

        assertEquals(emptyList(), winnersInList(mockMvc, winnerItemId), "토너먼트를 지웠는데 칩이 남았다")
    }

    @Test
    fun `같은 토너먼트라도 다른 참여자가 고른 상품은 내 칩이 되지 않는다`() {
        val mockMvc = buildMockMvc()
        saveUser(ownerId, "주최자")
        saveUser(guestPlayerId, "참여자")
        val itemA = saveWishItem(ownerId, "주최자가 고를 상품")
        val itemB = saveWishItem(ownerId, "참여자가 고를 상품")
        // 참여자는 시작 전에 초대로 들어온다 — 시작 뒤에는 합류할 수 없다.
        val tournamentId = startTournamentWith(mockMvc, listOf(itemA, itemB), invite = guestPlayerId)
        val cards = tournamentItemIdsOf(tournamentId)

        // 같은 판을 둘이 각자 플레이해 서로 다른 결론을 낸다. 참여자는 자기 start 로 플레이를 열어야 한다(#1027).
        playFinal(mockMvc, tournamentId, cards, winner = cardIdOf(cards, itemA), player = ownerId)
        startPlaying(mockMvc, tournamentId, guestPlayerId)
        playFinal(mockMvc, tournamentId, cards, winner = cardIdOf(cards, itemB), player = guestPlayerId)

        // 내 위시 목록은 내 플레이만 반영한다. 참여자가 고른 B 가 섞이면 "내가 선택한 1등" 이 아니게 된다.
        assertEquals(listOf(tournamentId), winnersInList(mockMvc, itemA), "내가 고른 상품에 칩이 없다")
        assertEquals(emptyList(), winnersInList(mockMvc, itemB), "다른 참여자가 고른 상품이 내 칩으로 새어 들어왔다")
    }

    @Test
    fun `토너먼트를 한 번도 돌리지 않으면 칩이 없다`() {
        val mockMvc = buildMockMvc()
        saveUser(ownerId, "주최자")
        val itemId = saveWishItem(ownerId, "담기만 한 상품")

        assertEquals(emptyList(), winnersInList(mockMvc, itemId))
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────────────

    // 목록에서 그 상품이 이긴 토너먼트 id 들(응답 순서 그대로 — 최신 우승이 앞).
    private fun winnersInList(
        mockMvc: MockMvc,
        itemId: Long,
    ): List<Long> {
        val result =
            mockMvc
                .perform(
                    get("/api/v1/wishlists")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(ownerId)),
                ).andExpect(status().isOk)
                .andReturn()
        val entries = objectMapper.readTree(result.response.contentAsString)["data"]
        val entry =
            entries.firstOrNull { it["item"]["id"].asLong() == itemId }
                ?: error("위시 목록에 item $itemId 가 없다")
        // JsonNode 자체의 map(Jackson 3)이 Kotlin 의 Iterable.map 보다 먼저 잡혀 원소 변환이 안 된다 —
        // 인덱스로 훑는 이 repo 의 선례(OpenApiDocsIntegrationTest 등)를 따른다.
        val ids = entry["winnerTournamentIds"]
        return (0 until ids.size()).map { ids.path(it).asLong() }
    }

    private fun winnersInDetail(
        mockMvc: MockMvc,
        wishId: Long,
    ): List<Long> {
        val result =
            mockMvc
                .perform(
                    get("/api/v1/wishlists/$wishId")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(ownerId)),
                ).andExpect(status().isOk)
                .andReturn()
        val ids = objectMapper.readTree(result.response.contentAsString)["data"]["winnerTournamentIds"]
        return (0 until ids.size()).map { ids.path(it).asLong() }
    }

    private fun wishIdOf(
        userId: UUID,
        itemId: Long,
    ): Long =
        wishJpaRepository
            .findByUserIdAndDeletedAtIsNullOrderByIdDesc(
                userId,
                org.springframework.data.domain.Limit
                    .of(100),
            ).first { it.itemId == itemId }
            .getId()

    // 결승 한 판으로 끝나는 토너먼트를 만들어 시작까지 한다(아이템 2개 = 첫 매치가 곧 결승).
    // invite 를 주면 그 사용자를 초대 코드로 합류시킨다 — 시작 전에만 가능하므로 여기서 함께 처리한다.
    private fun startTournamentWith(
        mockMvc: MockMvc,
        itemIds: List<Long>,
        invite: UUID? = null,
    ): Long {
        val created =
            mockMvc
                .perform(
                    post("/api/v1/tournaments")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"우승 칩 테스트"}"""),
                ).andReturn()
        val data = objectMapper.readTree(created.response.contentAsString)["data"]
        val tournamentId = data["tournamentId"].asLong()
        invite?.let { guest ->
            val code = data["inviteCode"].asText()
            mockMvc
                .perform(
                    post("/api/v1/tournaments/$tournamentId/join")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(guest))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"inviteCode":"$code"}"""),
                ).andExpect(status().isOk)
        }
        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/items/wish")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(ownerId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"itemIds":${itemIds.joinToString(",", "[", "]")}}"""),
            ).andExpect(status().isOk)
        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/start")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(ownerId)),
            ).andExpect(status().isOk)
        return tournamentId
    }

    // 참여자의 "시작" 은 주최자의 시작과 다른 일이다 — 자기 참여 행을 플레이 시작으로 전이시킨다(#1027).
    // 이걸 빼면 참여 행이 PENDING 에 머물러 매치 기록이 409 로 막힌다.
    private fun startPlaying(
        mockMvc: MockMvc,
        tournamentId: Long,
        player: UUID,
    ) {
        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/start")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(player)),
            ).andExpect(status().isOk)
    }

    private fun playFinal(
        mockMvc: MockMvc,
        tournamentId: Long,
        cards: List<Long>,
        winner: Long,
        player: UUID = ownerId,
    ) {
        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/matches")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(player))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"currentRound":2,"firstTournamentItemId":${cards[0]},""" +
                            """"secondTournamentItemId":${cards[1]},"selectedTournamentItemId":$winner}""",
                    ),
            ).andExpect(status().isOk)
    }

    private fun tournamentItemIdsOf(tournamentId: Long): List<Long> =
        tournamentItemJpaRepository
            .findAllByTournamentIdAndNotDeleted(tournamentId)
            .map { it.getId() }

    private fun cardIdOf(
        cards: List<Long>,
        itemId: Long,
    ): Long = cards.first { cardId -> tournamentItemJpaRepository.findById(cardId).orElseThrow().itemId == itemId }

    private fun saveWishItem(
        userId: UUID,
        name: String,
    ): Long {
        // 필요한 것은 "READY 인 위시 아이템" 이라는 상태뿐이라 등록 API 를 타지 않고 행을 직접 심는다.
        val item = itemJpaRepository.save(imageItem())
        itemSnapshotJpaRepository.save(itemVersion(itemId = item.getId(), name = name, price = 10_000, createdBy = userId))
        wishJpaRepository.save(Wish(userId = userId, itemId = item.getId()))
        return item.getId()
    }

    private fun saveUser(
        id: UUID,
        nickname: String,
    ): User =
        userJpaRepository.save(
            User(
                id = id,
                nickname = nickname,
                profileImage = "https://img.example.com/p.png",
                identityType = IdentityType.MEMBER,
            ),
        )

    private fun buildMockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(springSecurity())
            .build()

    private fun authHeader(userId: UUID): String =
        "Bearer ${jwtProvider.generateAccessToken(userId, IdentityType.MEMBER)}"
}
