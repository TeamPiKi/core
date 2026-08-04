package com.depromeet.piki.tournament.controller

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.item.repository.ItemJpaRepository
import com.depromeet.piki.item.repository.ItemSnapshotJpaRepository
import com.depromeet.piki.item.service.ItemParsingService
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.User
import com.depromeet.piki.user.repository.UserJpaRepository
import com.depromeet.piki.wishlist.service.WishPersistenceService
import org.junit.jupiter.api.Test
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

// GET /api/v1/tournaments?playType= 필터(#836) 통합 테스트.
// 판정 규칙(TournamentJpaRepository.findVisibleByUserId 주석 참조):
//   CLONE(sourceTournamentId 값 존재) → SOCIAL (상태 무관)
//   ROOT + 참여자 2명 이상 → SOCIAL
//   ROOT + 나 혼자(참여자 1명) → SOLO
//   playType 미지정 → 전체. status 와는 AND 조합.
@Transactional
class TournamentPlayTypeFilterIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var webApplicationContext: WebApplicationContext

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var userJpaRepository: UserJpaRepository

    @Autowired private lateinit var itemJpaRepository: ItemJpaRepository

    @Autowired private lateinit var itemSnapshotJpaRepository: ItemSnapshotJpaRepository

    @Autowired private lateinit var jwtProvider: JwtProvider

    @Autowired private lateinit var wishPersistenceService: WishPersistenceService

    @Autowired private lateinit var itemParsingService: ItemParsingService

    private val userId: UUID = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val otherUserId: UUID = UUID.fromString("99999999-8888-7777-6666-555555555555")

    private fun authHeader(userId: UUID): String =
        "Bearer ${jwtProvider.generateAccessToken(userId, IdentityType.MEMBER)}"

    private fun buildMockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    private fun saveUser(
        id: UUID,
        profileImage: String,
        nickname: String = "테스트유저",
    ): User =
        userJpaRepository.save(
            User(id = id, nickname = nickname, profileImage = profileImage, identityType = IdentityType.MEMBER),
        )

    private fun createTournament(
        mockMvc: MockMvc,
        owner: UUID,
        name: String = "테스트 토너먼트",
    ): Long {
        val result =
            mockMvc
                .perform(
                    post("/api/v1/tournaments")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"$name"}"""),
                ).andReturn()
        return objectMapper.readTree(result.response.contentAsString)["data"]["tournamentId"].asLong()
    }

    private fun joinTournament(
        mockMvc: MockMvc,
        tournamentId: Long,
        joiner: UUID,
    ) {
        mockMvc.perform(
            post("/api/v1/tournaments/$tournamentId/join")
                .header(HttpHeaders.AUTHORIZATION, authHeader(joiner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"inviteCode":null}"""),
        )
    }

    private fun saveWishItem(owner: UUID, name: String = "테스트 아이템", price: Int = 10_000): Long {
        val result = wishPersistenceService.persistPendingImages(owner, listOf("items/raw/${UUID.randomUUID()}.png")).first()
        itemSnapshotJpaRepository.findById(result.snapshot.getId()).get().markProcessing()
        itemParsingService.markReady(
            result.snapshot.getId(),
            ProductSnapshot(name = name, price = price, currency = "KRW", imageUrl = "https://img.example.com/a.png"),
            expectedAttempt = 0,
        )
        return result.item.getId()
    }

    private fun addItemsToTournament(
        mockMvc: MockMvc,
        tournamentId: Long,
        owner: UUID,
        vararg itemIds: Long,
    ) {
        mockMvc.perform(
            post("/api/v1/tournaments/$tournamentId/items/wish")
                .header(HttpHeaders.AUTHORIZATION, authHeader(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"itemIds":${itemIds.joinToString(",", "[", "]")}}"""),
        )
    }

    // ROOT 를 완료(COMPLETED)까지 진행 — play-link 생성은 COMPLETED 전제이므로 CLONE 픽스처에 필요하다.
    private fun completeTournament(
        mockMvc: MockMvc,
        owner: UUID,
    ): Long {
        val tournamentId = createTournament(mockMvc, owner)
        val item1Id = saveWishItem(owner, name = "아이템1")
        val item2Id = saveWishItem(owner, name = "아이템2")
        addItemsToTournament(mockMvc, tournamentId, owner, item1Id, item2Id)
        mockMvc.perform(
            post("/api/v1/tournaments/$tournamentId/start")
                .header(HttpHeaders.AUTHORIZATION, authHeader(owner)),
        )
        val startResult =
            mockMvc
                .perform(
                    get("/api/v1/tournaments/$tournamentId")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(owner)),
                ).andReturn()
        val items = objectMapper.readTree(startResult.response.contentAsString)["data"]["inProgress"]["remainingItems"]
        val ti1 = items[0]["tournamentItemId"].asLong()
        val ti2 = items[1]["tournamentItemId"].asLong()
        mockMvc.perform(
            post("/api/v1/tournaments/$tournamentId/matches")
                .header(HttpHeaders.AUTHORIZATION, authHeader(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentRound":2,"firstTournamentItemId":$ti1,"secondTournamentItemId":$ti2,"selectedTournamentItemId":$ti1}"""),
        )
        return tournamentId
    }

    // CLONE 픽스처 — sourceTournamentId owner(rootOwner) 가 완료한 ROOT 에 play-link 를 발급하고,
    // cloneOwner 가 from-play-link 로 자기 소유 CLONE 을 만든다(기존 TournamentIntegrationTest 픽스처 패턴 재사용).
    private fun createCloneFromPlayLink(
        mockMvc: MockMvc,
        rootOwner: UUID,
        cloneOwner: UUID,
    ): Long {
        val rootId = completeTournament(mockMvc, rootOwner)
        mockMvc.perform(
            post("/api/v1/tournaments/$rootId/play-link")
                .header(HttpHeaders.AUTHORIZATION, authHeader(rootOwner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
        val cloneResult =
            mockMvc
                .perform(
                    post("/api/v1/tournaments/$rootId/from-play-link")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(cloneOwner)),
                ).andReturn()
        return objectMapper.readTree(cloneResult.response.contentAsString)["data"].asLong()
    }

    @Test
    fun `playType=SOLO 는 나 혼자인 ROOT 만 반환한다`() {
        val mockMvc = buildMockMvc()
        saveUser(userId, "https://cdn.example.com/user.jpg", "나")
        saveUser(otherUserId, "https://cdn.example.com/other.jpg", "다른유저")
        val rootAId = createTournament(mockMvc, userId, "혼자 ROOT")
        val rootBId = createTournament(mockMvc, userId, "함께 ROOT")
        joinTournament(mockMvc, rootBId, otherUserId)

        mockMvc
            .perform(
                get("/api/v1/tournaments")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                    .param("playType", "SOLO"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].tournamentId").value(rootAId))
            .andExpect(jsonPath("$.data[?(@.tournamentId == $rootBId)]").doesNotExist())
    }

    @Test
    fun `playType=SOCIAL 은 참여자 2명 이상 ROOT 와 CLONE 을 반환한다`() {
        val mockMvc = buildMockMvc()
        saveUser(userId, "https://cdn.example.com/user.jpg", "나")
        saveUser(otherUserId, "https://cdn.example.com/other.jpg", "다른유저")
        val rootAId = createTournament(mockMvc, userId, "혼자 ROOT")
        val rootBId = createTournament(mockMvc, userId, "함께 ROOT")
        joinTournament(mockMvc, rootBId, otherUserId)
        // CLONE-C: otherUserId 가 완료한 ROOT 의 play-link 를 U 가 from-play-link 로 받아 U 소유 CLONE 을 만든다.
        // CLONE 은 참여자 1명(U 혼자)이지만 sourceTournamentId 가 있어 SOCIAL — 참여자 수 규칙만 적용하면
        // SOLO 로 오분류되는 경계 케이스를 이 단언이 고정한다.
        val cloneCId = createCloneFromPlayLink(mockMvc, rootOwner = otherUserId, cloneOwner = userId)

        mockMvc
            .perform(
                get("/api/v1/tournaments")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                    .param("playType", "SOCIAL"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data[?(@.tournamentId == $rootBId)]").exists())
            .andExpect(jsonPath("$.data[?(@.tournamentId == $cloneCId)]").exists())
            .andExpect(jsonPath("$.data[?(@.tournamentId == $rootAId)]").doesNotExist())
            .andExpect(jsonPath("$.data.length()").value(2))
    }

    @Test
    fun `playType 미지정이면 전체가 반환된다`() {
        val mockMvc = buildMockMvc()
        saveUser(userId, "https://cdn.example.com/user.jpg", "나")
        saveUser(otherUserId, "https://cdn.example.com/other.jpg", "다른유저")
        val rootAId = createTournament(mockMvc, userId, "혼자 ROOT")
        val rootBId = createTournament(mockMvc, userId, "함께 ROOT")
        joinTournament(mockMvc, rootBId, otherUserId)

        mockMvc
            .perform(
                get("/api/v1/tournaments")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[?(@.tournamentId == $rootAId)]").exists())
            .andExpect(jsonPath("$.data[?(@.tournamentId == $rootBId)]").exists())
    }

    @Test
    fun `PENDING 에서 다른 유저가 참여하면 SOLO 에서 빠지고 SOCIAL 로 이동한다`() {
        val mockMvc = buildMockMvc()
        saveUser(userId, "https://cdn.example.com/user.jpg", "나")
        saveUser(otherUserId, "https://cdn.example.com/other.jpg", "다른유저")
        val rootAId = createTournament(mockMvc, userId, "전이 ROOT")

        mockMvc
            .perform(
                get("/api/v1/tournaments")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                    .param("status", "PENDING")
                    .param("playType", "SOLO"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].tournamentId").value(rootAId))

        joinTournament(mockMvc, rootAId, otherUserId)

        mockMvc
            .perform(
                get("/api/v1/tournaments")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                    .param("status", "PENDING")
                    .param("playType", "SOLO"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(0))

        mockMvc
            .perform(
                get("/api/v1/tournaments")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                    .param("status", "PENDING")
                    .param("playType", "SOCIAL"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].tournamentId").value(rootAId))
    }
}
