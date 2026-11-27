package com.depromeet.piki.explore.controller

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.explore.domain.ExploreErrorCode
import com.depromeet.piki.explore.service.ExploreFeedRefresher
import com.depromeet.piki.item.repository.ItemJpaRepository
import com.depromeet.piki.item.repository.ItemSnapshotJpaRepository
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.imageItem
import com.depromeet.piki.support.itemVersion
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.tournament.domain.Tournament
import com.depromeet.piki.tournament.domain.TournamentHistory
import com.depromeet.piki.tournament.domain.TournamentItem
import com.depromeet.piki.tournament.domain.TournamentUser
import com.depromeet.piki.tournament.repository.TournamentHistoryJpaRepository
import com.depromeet.piki.tournament.repository.TournamentItemJpaRepository
import com.depromeet.piki.tournament.repository.TournamentJpaRepository
import com.depromeet.piki.tournament.repository.TournamentUserJpaRepository
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.wishlist.domain.Wish
import com.depromeet.piki.wishlist.domain.WishErrorCode
import com.depromeet.piki.wishlist.repository.WishJpaRepository
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import java.time.LocalDateTime
import java.util.UUID

// 다른 테스트가 커밋한 1위도 피드에 섞인다. 커서를 이 테스트가 만든 1위 바로 위에 두어 그 구간만 본다.
// 토너먼트 진행 API 를 타지 않고 결승 기록을 직접 심는다. 필요한 것은 "누가 결승에서 무엇을 골랐나" 라는 상태뿐이다
@Transactional
class ExplorePicksIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var webApplicationContext: WebApplicationContext

    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired private lateinit var jwtProvider: JwtProvider

    @Autowired private lateinit var exploreFeedRefresher: ExploreFeedRefresher

    @Autowired private lateinit var itemJpaRepository: ItemJpaRepository

    @Autowired private lateinit var itemSnapshotJpaRepository: ItemSnapshotJpaRepository

    @Autowired private lateinit var tournamentJpaRepository: TournamentJpaRepository

    @Autowired private lateinit var tournamentUserJpaRepository: TournamentUserJpaRepository

    @Autowired private lateinit var tournamentItemJpaRepository: TournamentItemJpaRepository

    @Autowired private lateinit var wishJpaRepository: WishJpaRepository

    @Autowired private lateinit var tournamentHistoryJpaRepository: TournamentHistoryJpaRepository

    @Test
    fun `비로그인도 공개 회원의 1위를 토너먼트 이름과 함께 최근에 고른 순으로 받는다`() {
        val mockMvc = mockMvc()
        val member = user()
        val guest = user(identityType = IdentityType.GUEST)
        val hidden = user(isProfilePublic = false)
        val older = finalOf(tournament(member, "겨울 패딩 뭐 사지", machineItem("숏 패딩"), machineItem("롱 패딩")))
        finalOf(tournament(guest, "게스트 토너먼트", machineItem("게스트 1위"), machineItem("게스트 2위")))
        finalOf(tournament(hidden, "비공개 토너먼트", machineItem("비공개 1위"), machineItem("비공개 2위")))
        finalOf(tournament(member, "직접 고친 값", userInputItem(member), machineItem("상대")))
        val newer = finalOf(tournament(member, "생일 선물", machineItem("토트백"), machineItem("크로스백")))
        exploreFeedRefresher.refresh()

        mockMvc
            .perform(get("/api/v1/explore/picks").param("cursor", (newer.pickId + 1).toString()).param("size", "2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.updatedAt").exists())
            .andExpect(jsonPath("$.data.picks", hasSize<Any>(2)))
            .andExpect(jsonPath("$.data.picks[0].pickId").value(newer.pickId))
            .andExpect(jsonPath("$.data.picks[0].candidateId").value(newer.candidateId))
            .andExpect(jsonPath("$.data.picks[0].tournament.name").value("생일 선물"))
            .andExpect(jsonPath("$.data.picks[0].item.name").value("토트백"))
            .andExpect(jsonPath("$.data.picks[0].item.price").value(10_000))
            .andExpect(jsonPath("$.data.picks[0].saved").value(false))
            .andExpect(jsonPath("$.data.picks[0].pickedAt").exists())
            .andExpect(jsonPath("$.data.picks[1].pickId").value(older.pickId))
            .andExpect(jsonPath("$.data.picks[1].tournament.name").value("겨울 패딩 뭐 사지"))
    }

    @Test
    fun `차단 관계인 사람의 1위는 빠지고 내가 가진 상품은 saved 가 true 다`() {
        val mockMvc = mockMvc()
        val viewer = user()
        val blocked = user()
        val stranger = user()
        val sharedItem = machineItem("내가 가진 상품")
        saveWish(viewer, sharedItem)
        val visible = finalOf(tournament(stranger, "보이는 토너먼트", sharedItem, machineItem("상대")))
        val last = finalOf(tournament(blocked, "차단된 사람의 토너먼트", machineItem("차단 1위"), machineItem("차단 2위")))
        mockMvc.perform(post("/api/v1/blocks/$blocked").by(viewer)).andExpect(status().isOk)
        exploreFeedRefresher.refresh()

        mockMvc
            .perform(get("/api/v1/explore/picks").by(viewer).param("cursor", (last.pickId + 1).toString()).param("size", "1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.picks[0].pickId").value(visible.pickId))
            .andExpect(jsonPath("$.data.picks[0].saved").value(true))
    }

    @Test
    fun `결승이 아닌 기록과 지운 토너먼트의 1위는 나오지 않는다`() {
        val mockMvc = mockMvc()
        val member = user()
        val visible = finalOf(tournament(member, "보이는 토너먼트", machineItem("1위"), machineItem("2위")))
        val semifinal = tournament(member, "4강 기록", machineItem("4강 승자"), machineItem("4강 패자"))
        finalOf(semifinal, round = 4)
        val deleted = tournament(member, "지운 토너먼트", machineItem("지운 1위"), machineItem("지운 2위"))
        val deletedPick = finalOf(deleted)
        tournamentJpaRepository.findById(deleted.tournamentId).orElseThrow().deletedAt = LocalDateTime.now()
        val last = finalOf(semifinal, round = 4)
        exploreFeedRefresher.refresh()

        mockMvc
            .perform(get("/api/v1/explore/picks").param("cursor", (last.pickId + 1).toString()).param("size", "1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.picks[0].pickId").value(visible.pickId))
        mockMvc
            .perform(get("/api/v1/explore/picks/${deletedPick.pickId}"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(ExploreErrorCode.PICK_NOT_FOUND.code))
    }

    @Test
    fun `올린 사람이 추출값 위에 직접 고친 값을 쌓아도 탐색에는 추출값이 나온다`() {
        val mockMvc = mockMvc()
        val picker = user()
        val itemId = machineItem("추출한 이름")
        itemSnapshotJpaRepository.save(itemVersion(itemId = itemId, name = "직접 고친 이름", price = 1, userInput = true, createdBy = picker))
        val pick = finalOf(tournament(picker, "직접 고친 토너먼트", itemId, machineItem("상대")))
        exploreFeedRefresher.refresh()

        mockMvc
            .perform(get("/api/v1/explore/picks").param("cursor", (pick.pickId + 1).toString()).param("size", "1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.picks[0].pickId").value(pick.pickId))
            .andExpect(jsonPath("$.data.picks[0].item.name").value("추출한 이름"))
            .andExpect(jsonPath("$.data.picks[0].item.price").value(10_000))
        mockMvc
            .perform(get("/api/v1/explore/picks/${pick.pickId}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.item.name").value("추출한 이름"))
    }

    @Test
    fun `1위 상세는 고른 사람과 후보 썸네일을 1위부터 내주고 고른 사람이 비공개로 바꾸면 404 다`() {
        val mockMvc = mockMvc()
        val picker = user()
        val viewer = user()
        val tournament = tournament(picker, "겨울 패딩 뭐 사지", machineItem("숏 패딩"), machineItem("롱 패딩"), userInputItem(picker))
        val pick = finalOf(tournament, winner = tournament.candidateIds[1])

        mockMvc
            .perform(get("/api/v1/explore/picks/${pick.pickId}").by(viewer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.pickId").value(pick.pickId))
            .andExpect(jsonPath("$.data.item.name").value("롱 패딩"))
            .andExpect(jsonPath("$.data.saved").value(false))
            .andExpect(jsonPath("$.data.savedUserCount").value(0))
            .andExpect(jsonPath("$.data.picker.userId").value(picker.toString()))
            .andExpect(jsonPath("$.data.picker.relation").value("NOT_FRIEND"))
            .andExpect(jsonPath("$.data.picker.folder").doesNotExist())
            .andExpect(jsonPath("$.data.tournament.name").value("겨울 패딩 뭐 사지"))
            .andExpect(jsonPath("$.data.tournament.candidateCount").value(2))
            .andExpect(jsonPath("$.data.tournament.candidateImageUrls", hasSize<Any>(2)))

        val blockedViewer = user()
        mockMvc.perform(post("/api/v1/blocks/$picker").by(blockedViewer)).andExpect(status().isOk)
        mockMvc
            .perform(get("/api/v1/explore/picks/${pick.pickId}").by(blockedViewer))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(ExploreErrorCode.PICK_NOT_FOUND.code))

        mockMvc
            .perform(patch("/api/v1/users/me").by(picker).contentType("application/json").content("""{"isProfilePublic":false}"""))
            .andExpect(status().isOk)

        mockMvc
            .perform(get("/api/v1/explore/picks/${pick.pickId}").by(viewer))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(ExploreErrorCode.PICK_NOT_FOUND.code))
        mockMvc
            .perform(get("/api/v1/explore/picks/${pick.pickId}").by(picker))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.picker.relation").value("SELF"))
    }

    @Test
    fun `후보 목록은 1위를 맨 앞에 두고 직접 입력한 값밖에 없는 후보는 뺀다`() {
        val mockMvc = mockMvc()
        val picker = user()
        val viewer = user()
        val saved = machineItem("가진 후보")
        saveWish(viewer, saved)
        val tournament = tournament(picker, "생일 선물", saved, userInputItem(picker), machineItem("1위 후보"))
        val pick = finalOf(tournament, winner = tournament.candidateIds[2])

        mockMvc
            .perform(get("/api/v1/explore/picks/${pick.pickId}/candidates").by(viewer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.pickId").value(pick.pickId))
            .andExpect(jsonPath("$.data.tournament.name").value("생일 선물"))
            .andExpect(jsonPath("$.data.candidates", hasSize<Any>(2)))
            .andExpect(jsonPath("$.data.candidates[0].candidateId").value(tournament.candidateIds[2]))
            .andExpect(jsonPath("$.data.candidates[0].isPick").value(true))
            .andExpect(jsonPath("$.data.candidates[0].saved").value(false))
            .andExpect(jsonPath("$.data.candidates[1].candidateId").value(tournament.candidateIds[0]))
            .andExpect(jsonPath("$.data.candidates[1].isPick").value(false))
            .andExpect(jsonPath("$.data.candidates[1].saved").value(true))

        val guestsPick = finalOf(tournament(user(identityType = IdentityType.GUEST), "게스트", machineItem("a"), machineItem("b")))
        mockMvc
            .perform(get("/api/v1/explore/picks/${guestsPick.pickId}/candidates"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(ExploreErrorCode.PICK_NOT_FOUND.code))
    }

    @Test
    fun `후보를 담으면 내 위시가 생기고 다시 담으면 그대로 성공하며 다른 토너먼트의 후보는 404 다`() {
        val mockMvc = mockMvc()
        val picker = user()
        val viewer = user()
        val tournament = tournament(picker, "생일 선물", machineItem("1위"), machineItem("후보"))
        val pick = finalOf(tournament)
        val other = tournament(picker, "다른 토너먼트", machineItem("다른 1위"), machineItem("다른 후보"))
        val path = "/api/v1/explore/picks/${pick.pickId}/candidates/${tournament.candidateIds[1]}/copy"

        mockMvc
            .perform(post(path).by(viewer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.wishId").exists())
            .andExpect(jsonPath("$.data.alreadySaved").value(false))
        mockMvc
            .perform(post(path).by(viewer))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.alreadySaved").value(true))
        mockMvc
            .perform(get("/api/v1/explore/picks/${pick.pickId}/candidates").by(viewer))
            .andExpect(jsonPath("$.data.candidates[1].saved").value(true))

        mockMvc
            .perform(post("/api/v1/explore/picks/${pick.pickId}/candidates/${other.candidateIds[1]}/copy").by(viewer))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value(ExploreErrorCode.PICK_NOT_FOUND.code))
        val guest = user(identityType = IdentityType.GUEST)
        mockMvc
            .perform(post(path).by(guest, IdentityType.GUEST))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value(WishErrorCode.GUEST_CANNOT_USE_WISHLIST.code))
    }

    private data class TournamentFixture(
        val tournamentId: Long,
        val ownerTournamentUserId: Long,
        val candidateIds: List<Long>,
    )

    private data class PickFixture(
        val pickId: Long,
        val candidateId: Long,
    )

    private fun tournament(
        owner: UUID,
        name: String,
        vararg itemIds: Long,
    ): TournamentFixture {
        val tournament =
            tournamentJpaRepository.save(
                Tournament(
                    ownerTournamentUserId = 1,
                    name = name,
                    inviteCode = Tournament.generateInviteCode(),
                    inviteExpiresAt = LocalDateTime.now().plusDays(1),
                ),
            )
        val ownerRow = tournamentUserJpaRepository.save(TournamentUser(tournamentId = tournament.getId(), userId = owner))
        tournament.assignOwner(ownerRow.getId())
        val candidateIds =
            itemIds.map { itemId ->
                tournamentItemJpaRepository.save(TournamentItem(tournamentId = tournament.getId(), userId = owner, itemId = itemId)).getId()
            }
        return TournamentFixture(tournament.getId(), ownerRow.getId(), candidateIds)
    }

    private fun finalOf(
        tournament: TournamentFixture,
        winner: Long = tournament.candidateIds.first(),
        round: Int = Tournament.FINAL_ROUND_SIZE,
    ): PickFixture {
        val history =
            tournamentHistoryJpaRepository.save(
                TournamentHistory(
                    tournamentId = tournament.tournamentId,
                    tournamentUserId = tournament.ownerTournamentUserId,
                    currentRound = round,
                    firstTournamentItemId = tournament.candidateIds[0],
                    secondTournamentItemId = tournament.candidateIds[1],
                    selectedTournamentItemId = winner,
                ),
            )
        return PickFixture(history.getId(), winner)
    }

    private fun machineItem(name: String): Long {
        val item = itemJpaRepository.save(imageItem())
        itemSnapshotJpaRepository.save(itemVersion(itemId = item.getId(), name = name, price = 10_000))
        return item.getId()
    }

    private fun userInputItem(by: UUID): Long {
        val item = itemJpaRepository.save(imageItem())
        itemSnapshotJpaRepository.save(itemVersion(itemId = item.getId(), name = "직접 입력", price = 5_000, userInput = true, createdBy = by))
        return item.getId()
    }

    private fun saveWish(
        userId: UUID,
        itemId: Long,
    ) {
        wishJpaRepository.save(Wish(userId = userId, itemId = itemId))
    }

    private fun user(
        identityType: IdentityType = IdentityType.MEMBER,
        isProfilePublic: Boolean = true,
    ): UUID =
        UUID.randomUUID().also { userId ->
            jdbcTemplate.update(
                "INSERT INTO users (id, nickname, identity_type, is_profile_public, created_at, updated_at) VALUES (?, ?, ?, ?, NOW(6), NOW(6))",
                uuidToBytes(userId),
                userId.toString().take(10),
                identityType.name,
                isProfilePublic,
            )
        }

    private fun mockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    private fun MockHttpServletRequestBuilder.by(
        userId: UUID,
        identityType: IdentityType = IdentityType.MEMBER,
    ): MockHttpServletRequestBuilder = header(HttpHeaders.AUTHORIZATION, "Bearer ${jwtProvider.generateAccessToken(userId, identityType)}")
}
