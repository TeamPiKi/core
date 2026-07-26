package com.depromeet.piki.tournament.controller

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.repository.ItemJpaRepository
import com.depromeet.piki.item.repository.ItemSnapshotJpaRepository
import com.depromeet.piki.item.service.ItemParsingService
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.ProductSnapshot
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubImageParsingWorker
import com.depromeet.piki.support.StubImageStorage
import com.depromeet.piki.support.StubItemParsingWorker
import com.depromeet.piki.support.StubRefreshTokenStore
import com.depromeet.piki.tournament.domain.Tournament
import com.depromeet.piki.tournament.domain.TournamentItem
import com.depromeet.piki.tournament.domain.TournamentUser
import com.depromeet.piki.tournament.event.TournamentCompleted
import com.depromeet.piki.tournament.event.TournamentItemAdded
import com.depromeet.piki.tournament.event.TournamentJoined
import com.depromeet.piki.tournament.event.TournamentPlayedFromLink
import com.depromeet.piki.tournament.event.TournamentResultReady
import com.depromeet.piki.tournament.event.TournamentStarted
import com.depromeet.piki.tournament.repository.TournamentItemJpaRepository
import com.depromeet.piki.tournament.repository.TournamentJpaRepository
import com.depromeet.piki.tournament.repository.TournamentUserJpaRepository
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.user.domain.User
import com.depromeet.piki.user.repository.UserJpaRepository
import com.depromeet.piki.wishlist.domain.Wish
import com.depromeet.piki.wishlist.repository.WishJpaRepository
import com.depromeet.piki.wishlist.service.WishPersistenceService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.hamcrest.Matchers.nullValue
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RecordApplicationEvents
@Transactional
class TournamentIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var webApplicationContext: WebApplicationContext

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var tournamentItemJpaRepository: TournamentItemJpaRepository

    @Autowired private lateinit var tournamentJpaRepository: TournamentJpaRepository

    @Autowired private lateinit var tournamentUserJpaRepository: TournamentUserJpaRepository

    @Autowired private lateinit var userJpaRepository: UserJpaRepository

    @Autowired private lateinit var itemJpaRepository: ItemJpaRepository

    @Autowired private lateinit var itemSnapshotJpaRepository: ItemSnapshotJpaRepository

    @Autowired private lateinit var jwtProvider: JwtProvider

    @Autowired private lateinit var wishPersistenceService: WishPersistenceService

    @Autowired private lateinit var itemParsingService: ItemParsingService

    @Autowired private lateinit var wishJpaRepository: WishJpaRepository

    @Autowired private lateinit var stubItemParsingWorker: StubItemParsingWorker

    @Autowired private lateinit var stubImageParsingWorker: StubImageParsingWorker

    @Autowired private lateinit var stubImageStorage: StubImageStorage

    @Autowired private lateinit var stubRefreshTokenStore: StubRefreshTokenStore

    // 발행 검증용 — 같은 스레드(MockMvc 컨트롤러 실행)에서 publish 된 도메인 이벤트를 기록한다.
    // AFTER_COMMIT 리스너 발화(별도 스레드, 트랜잭션 롤백 무관) 여부와 독립적으로 "서비스가 이벤트를 쐈는가" 만 본다.
    @Autowired private lateinit var applicationEvents: ApplicationEvents

    private val userId: UUID = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val otherUserId: UUID = UUID.fromString("99999999-8888-7777-6666-555555555555")
    private val userProfileImage = "https://cdn.example.com/profiles/user.jpg"

    private fun authHeader(userId: UUID): String =
        "Bearer ${jwtProvider.generateAccessToken(userId, IdentityType.MEMBER)}"

    @Test
    fun `POST tournaments 는 201 과 함께 tournamentId inviteCode inviteExpiresAt 을 반환한다`() {
        val mockMvc = buildMockMvc()

        mockMvc
            .perform(
                post("/api/v1/tournaments")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"테스트 토너먼트"}"""),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.tournamentId").isNumber)
            .andExpect(jsonPath("$.data.inviteCode").isString)
            .andExpect(jsonPath("$.data.inviteExpiresAt").isString)
    }

    @Test
    fun `POST tournaments 에서 inviteDurationMinutes 를 지정하면 해당 시간으로 만료 시각이 설정된다`() {
        val mockMvc = buildMockMvc()
        val before = LocalDateTime.now()

        val result = mockMvc
            .perform(
                post("/api/v1/tournaments")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"테스트 토너먼트","inviteDurationMinutes":60}"""),
            ).andExpect(status().isCreated)
            .andReturn()

        val expiresAtStr = objectMapper.readTree(result.response.contentAsString)["data"]["inviteExpiresAt"].asText()
        // 모든 시각 응답은 KST(+09:00) 오프셋으로 직렬화된다. (contract 회귀 가드)
        assertTrue(expiresAtStr.endsWith("+09:00"), "inviteExpiresAt 는 KST(+09:00) 오프셋으로 직렬화돼야 한다: $expiresAtStr")
        // 서버는 저장값(LocalDateTime)을 UTC 로 해석해 직렬화한다. 기대값도 같은 UTC 해석으로 instant 를 만들어 비교하면
        // JVM 기본 타임존(로컬 KST · CI UTC)과 무관하게 만료까지 실제 경과가 60분인지 검증된다.
        val expiresAt = java.time.OffsetDateTime.parse(expiresAtStr, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
        val expectedMin = before.toInstant(java.time.ZoneOffset.UTC).plus(java.time.Duration.ofMinutes(60))
        val expectedMax = LocalDateTime.now().toInstant(java.time.ZoneOffset.UTC).plus(java.time.Duration.ofMinutes(60))
        assertTrue(!expiresAt.isBefore(expectedMin) && !expiresAt.isAfter(expectedMax))
    }

    @Test
    fun `POST tournaments 에서 inviteDurationMinutes 가 0 이면 400 을 반환한다`() {
        val mockMvc = buildMockMvc()

        mockMvc
            .perform(
                post("/api/v1/tournaments")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"테스트 토너먼트","inviteDurationMinutes":0}"""),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `POST tournaments-id-join 은 유효한 초대 코드로 토너먼트 참여에 성공한다`() {
        val mockMvc = buildMockMvc()
        val (tournamentId, inviteCode) = createTournamentWithInviteCode(mockMvc)
        saveUser(otherUserId, "https://cdn.example.com/other.jpg", "다른유저")

        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/join")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"inviteCode":"$inviteCode"}"""),
            ).andExpect(status().isOk)

        val participants = tournamentUserJpaRepository.findByTournamentIdAndDeletedAtIsNull(tournamentId)
        assertEquals(2, participants.size)
    }

    @Test
    fun `POST tournaments-id-join 은 잘못된 초대 코드면 400 을 반환한다`() {
        val mockMvc = buildMockMvc()
        val (tournamentId, _) = createTournamentWithInviteCode(mockMvc)
        saveUser(otherUserId, "https://cdn.example.com/other.jpg", "다른유저")

        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/join")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"inviteCode":"000000"}"""),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `POST tournaments-id-join 은 이미 참여 중이면 409 를 반환한다`() {
        val mockMvc = buildMockMvc()
        val (tournamentId, inviteCode) = createTournamentWithInviteCode(mockMvc)

        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/join")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"inviteCode":"$inviteCode"}"""),
            ).andExpect(status().isConflict)
            // tournament 이관(#764)으로 409(CONFLICT)가 처음으로 도메인 code 를 싣는다 — alreadyParticipant → TOURNAMENT-022.
            .andExpect(jsonPath("$.code").value("TOURNAMENT-022"))
    }

    @Test
    fun `POST tournaments-id-join-guest 는 새 게스트 계정을 생성하고 토너먼트에 참여한다`() {
        val mockMvc = buildMockMvc()
        val (tournamentId, inviteCode) = createTournamentWithInviteCode(mockMvc)

        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/join/guest")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"inviteCode":"$inviteCode","nickname":"새친구"}"""),
            ).andExpect(status().isCreated)
            .andExpect(cookie().exists("access_token"))
            .andExpect(cookie().httpOnly("access_token", true))
            .andExpect(cookie().exists("refresh_token"))
            .andExpect(cookie().httpOnly("refresh_token", true))
            .andExpect(jsonPath("$.data.accessToken").value(nullValue()))
            .andExpect(jsonPath("$.data.refreshToken").value(nullValue()))
            .andExpect(jsonPath("$.data.userId").isString)
            .andExpect(jsonPath("$.data.nickname").value("새친구"))
            .andExpect(jsonPath("$.data.tournamentId").value(tournamentId))

        val participants = tournamentUserJpaRepository.findByTournamentIdAndDeletedAtIsNull(tournamentId)
        assertEquals(2, participants.size)
    }

    @Test
    fun `POST tournaments-id-join-guest 는 이미 점유된 닉네임이면 409 를 반환한다`() {
        // createGuestWithNickname 은 existsByNickname pre-check 가 없어, 충돌이 save 시점에 난다.
        // saveAndFlush 로 그 unique 충돌을 같은 메서드 안에서 끌어올려 duplicateNickname(409)으로 변환하는지 검증한다(#636).
        val mockMvc = buildMockMvc()
        val (tournamentId, inviteCode) = createTournamentWithInviteCode(mockMvc)

        // 첫 게스트가 "철수" 로 합류해 닉네임을 점유한다.
        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/join/guest")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"inviteCode":"$inviteCode","nickname":"철수"}"""),
            ).andExpect(status().isCreated)

        // 같은 닉네임으로 또 합류하면 닉네임 unique 충돌 → 409 (saveAndFlush 변환, 500 아님).
        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/join/guest")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"inviteCode":"$inviteCode","nickname":"철수"}"""),
            ).andExpect(status().isConflict)
    }

    @Test
    fun `POST tournaments-id-join-guest 는 JWT 없이도 호출 가능하다`() {
        val mockMvc = buildMockMvc()
        val (tournamentId, inviteCode) = createTournamentWithInviteCode(mockMvc)

        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/join/guest")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"inviteCode":"$inviteCode","nickname":"무인증친구"}"""),
            ).andExpect(status().isCreated)
    }

    @Test
    fun `POST tournaments-id-join-guest 는 잘못된 초대 코드면 400 을 반환한다`() {
        val mockMvc = buildMockMvc()
        val (tournamentId, _) = createTournamentWithInviteCode(mockMvc)

        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/join/guest")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"inviteCode":"000000","nickname":"새친구"}"""),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `POST tournaments-id-join-guest 는 닉네임이 비어 있으면 400 을 반환한다`() {
        val mockMvc = buildMockMvc()
        val (tournamentId, inviteCode) = createTournamentWithInviteCode(mockMvc)

        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/join/guest")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"inviteCode":"$inviteCode","nickname":""}"""),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `POST tournaments-id-join-guest 는 토큰 발급 실패 시 게스트 계정과 참여 레코드를 보상 삭제한다`() {
        val mockMvc = buildMockMvc()
        val (tournamentId, inviteCode) = createTournamentWithInviteCode(mockMvc)
        stubRefreshTokenStore.onSave = { _, _ -> throw RuntimeException("Redis 장애 시뮬레이션") }
        try {
            mockMvc
                .perform(
                    post("/api/v1/tournaments/$tournamentId/join/guest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"inviteCode":"$inviteCode","nickname":"유령친구"}"""),
                ).andExpect(status().isInternalServerError)
        } finally {
            stubRefreshTokenStore.reset()
        }

        assertEquals(1, tournamentUserJpaRepository.countByTournamentIdAndDeletedAtIsNull(tournamentId))
    }

    @Test
    fun `POST tournaments-id-items 는 참여자이면 200 과 함께 tournamentItemIds 를 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)
        val item1Id = saveWishItem()
        val item2Id = saveWishItem()

        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/items/wish")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"itemIds":[$item1Id,$item2Id]}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.tournamentItemIds").isArray)
            .andExpect(jsonPath("$.data.tournamentItemIds.length()").value(2))
    }

    @Test
    fun `POST tournaments-id-items 는 아이템 1개도 추가할 수 있다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)
        val itemId = saveWishItem()

        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/items/wish")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"itemIds":[$itemId]}"""),
            ).andExpect(status().isOk)
    }

    @Test
    fun `POST tournaments-id-items 에서 존재하지 않는 아이템 ID 이면 404 를 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)
        // 위시에는 등록되어 있지만 item 테이블에는 없는 ID — wish 확인 통과 후 item 존재 확인에서 404.
        // itemId 단일 출처는 snapshot 이므로, item 행 없이 itemId=999999 를 가리키는 snapshot 만 시딩해 wish 가 가리키게 한다(FK 없음).
        val danglingSnapshotId = itemSnapshotJpaRepository.save(ItemSnapshot.pending(999999L).apply { markProcessing() }).getId()
        wishJpaRepository.save(Wish(userId = userId, snapshotId = danglingSnapshotId))

        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/items/wish")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"itemIds":[999999]}"""),
            ).andExpect(status().isNotFound)
    }

    @Test
    fun `POST tournaments-id-items 에서 아직 파싱 중(PROCESSING)인 아이템이면 409 를 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)
        val processingItemId = itemJpaRepository.save(Item()).getId()
        // 활성 snapshot 이 PROCESSING — 표시값·상태는 snapshot 소관이라 PROCESSING snapshot 을 만들어 wish 가 가리키게 한다.
        val processingSnapshotId =
            itemSnapshotJpaRepository.save(ItemSnapshot.pending(processingItemId).apply { markProcessing() }).getId()
        // 위시에도 등록 — wish 확인 통과 후 READY 상태 확인에서 409
        wishJpaRepository.save(Wish(userId = userId, snapshotId = processingSnapshotId))

        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/items/wish")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"itemIds":[$processingItemId]}"""),
            ).andExpect(status().isConflict)
    }

    @Test
    fun `POST tournaments-id-items 에서 빈 itemIds 는 400 을 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)

        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/items/wish")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"itemIds":[]}"""),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `POST tournaments-id-items 에서 토너먼트 참여자가 아니면 403 을 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)

        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/items/wish")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"itemIds":[100,200]}"""),
            ).andExpect(status().isForbidden)
            // 참여자가 아닌 경우 forbiddenTournament → TOURNAMENT-001 (도메인 code 회귀 가드).
            .andExpect(jsonPath("$.code").value("TOURNAMENT-001"))
    }

    @Test
    fun `POST tournaments-id-items 에서 플레이링크 복제 토너먼트이면 403 을 반환한다`() {
        val mockMvc = buildMockMvc()
        saveUser(otherUserId, "https://cdn.example.com/other.jpg", "다른유저")
        val (sourceTournamentId, _, _) = completeTournamentWith2Items(mockMvc)
        mockMvc.perform(
            post("/api/v1/tournaments/$sourceTournamentId/play-link")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
        val cloneResult = mockMvc.perform(
            post("/api/v1/tournaments/$sourceTournamentId/from-play-link")
                .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId)),
        ).andReturn()
        val cloneId = objectMapper.readTree(cloneResult.response.contentAsString)["data"].asLong()
        val wishItemId = saveWishItem(owner = otherUserId)

        mockMvc
            .perform(
                post("/api/v1/tournaments/$cloneId/items/wish")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"itemIds":[$wishItemId]}"""),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `POST tournaments-id-start 는 아이템이 있는 PENDING 토너먼트를 시작하고 가격 오름차순 정렬된 아이템 목록을 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)
        // 비싼 아이템을 먼저 추가 — DB 조회 순서(id ASC)와 가격 순서가 달라야 정렬이 실제로 검증된다
        val item2Id = saveWishItem(name = "아디다스 울트라부스트", price = 189_000)
        val item1Id = saveWishItem(name = "나이키 에어맥스", price = 129_000)
        addItemsToTournament(mockMvc, tournamentId, userId, item2Id, item1Id)

        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/start")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items").isArray)
            .andExpect(jsonPath("$.data.items.length()").value(2))
            .andExpect(jsonPath("$.data.items[0].tournamentItemId").isNumber)
            .andExpect(jsonPath("$.data.items[0].name").value("나이키 에어맥스"))
            .andExpect(jsonPath("$.data.items[0].price").value(129_000))
            .andExpect(jsonPath("$.data.items[0].currency").value("KRW"))
            .andExpect(jsonPath("$.data.items[1].tournamentItemId").isNumber)
            .andExpect(jsonPath("$.data.items[1].name").value("아디다스 울트라부스트"))
            .andExpect(jsonPath("$.data.items[1].price").value(189_000))
            .andExpect(jsonPath("$.data.items[1].currency").value("KRW"))
    }

    @Test
    fun `POST tournaments-id-start 에서 소유자가 아니면 403 을 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)
        addItemsToTournament(mockMvc, tournamentId, userId, 100L, 200L)

        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/start")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId)),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `POST tournaments-id-start 에서 아이템이 없으면 400 을 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)

        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/start")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `POST tournaments-id-matches 는 IN_PROGRESS 토너먼트에 매치를 기록하고 200 을 반환한다`() {
        val mockMvc = buildMockMvc()
        val (tournamentId, item1Id, item2Id) = startTournamentWith2Items(mockMvc)

        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/matches")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"currentRound":2,"firstTournamentItemId":$item1Id,"secondTournamentItemId":$item2Id,"selectedTournamentItemId":$item1Id}""",
                    ),
            ).andExpect(status().isOk)
    }

    @Test
    fun `POST tournaments-id-matches 에서 이미 COMPLETED 인 토너먼트이면 409 를 반환한다`() {
        val mockMvc = buildMockMvc()
        val (tournamentId, item1Id, item2Id) = startTournamentWith2Items(mockMvc)
        val matchBody =
            """{"currentRound":2,"firstTournamentItemId":$item1Id,"secondTournamentItemId":$item2Id,"selectedTournamentItemId":$item1Id}"""

        mockMvc.perform(
            post("/api/v1/tournaments/$tournamentId/matches")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(matchBody),
        )

        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/matches")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(matchBody),
            ).andExpect(status().isConflict)
    }

    @Test
    fun `POST tournaments-id-matches 에서 이미 탈락한 아이템으로 매치를 시도하면 409 를 반환한다`() {
        val mockMvc = buildMockMvc()
        val item1Id = saveWishItem(name = "아이템1", price = 10_000)
        val item2Id = saveWishItem(name = "아이템2", price = 20_000)
        val item3Id = saveWishItem(name = "아이템3", price = 30_000)
        val item4Id = saveWishItem(name = "아이템4", price = 40_000)
        val tournamentId = createTournament(mockMvc)
        addItemsToTournament(mockMvc, tournamentId, userId, item1Id, item2Id, item3Id, item4Id)
        mockMvc.perform(
            post("/api/v1/tournaments/$tournamentId/start")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
        )
        val items = tournamentItemJpaRepository.findAllByTournamentIdAndNotDeleted(tournamentId)
        val ti1 = items[0].getId()
        val ti2 = items[1].getId()
        val ti3 = items[2].getId()
        val ti4 = items[3].getId()

        // round-4 첫 번째 매치: ti1 승, ti2 탈락
        mockMvc.perform(
            post("/api/v1/tournaments/$tournamentId/matches")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentRound":4,"firstTournamentItemId":$ti1,"secondTournamentItemId":$ti2,"selectedTournamentItemId":$ti1}"""),
        ).andExpect(status().isOk)

        // 탈락한 ti2 로 다시 매치 시도 → 409
        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/matches")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"currentRound":4,"firstTournamentItemId":$ti2,"secondTournamentItemId":$ti3,"selectedTournamentItemId":$ti3}"""),
            ).andExpect(status().isConflict)
    }

    @Test
    fun `POST tournaments-id-matches 에서 currentRound 가 예상 라운드와 다르면 400 을 반환한다`() {
        val mockMvc = buildMockMvc()
        val (tournamentId, item1Id, item2Id) = startTournamentWith2Items(mockMvc)

        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/matches")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"currentRound":4,"firstTournamentItemId":$item1Id,"secondTournamentItemId":$item2Id,"selectedTournamentItemId":$item1Id}""",
                    ),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `POST tournaments-id-matches 에서 참가자가 아니면 403 을 반환한다`() {
        val mockMvc = buildMockMvc()
        val (tournamentId, item1Id, item2Id) = startTournamentWith2Items(mockMvc)

        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/matches")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"currentRound":2,"firstTournamentItemId":$item1Id,"secondTournamentItemId":$item2Id,"selectedTournamentItemId":$item1Id}""",
                    ),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `GET tournaments 는 내 토너먼트 목록을 200 과 함께 반환하고 참여자 프로필 이미지를 포함한다`() {
        val mockMvc = buildMockMvc()
        saveUser(userId, userProfileImage)
        createTournament(mockMvc, "토너먼트A")
        createTournament(mockMvc, "토너먼트B")

        mockMvc
            .perform(
                get("/api/v1/tournaments")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].tournamentId").isNumber)
            .andExpect(jsonPath("$.data[0].name").isString)
            .andExpect(jsonPath("$.data[0].status").isString)
            .andExpect(jsonPath("$.data[0].createdAt").isString)
            .andExpect(jsonPath("$.data[0].participantProfileImages").isArray)
            .andExpect(jsonPath("$.data[0].participantProfileImages[0]").value(userProfileImage))
    }

    @Test
    fun `GET tournaments 에서 status 필터를 지정하면 해당 상태만 반환된다`() {
        val mockMvc = buildMockMvc()
        saveUser(userId, userProfileImage)
        val pendingId = createTournament(mockMvc, "대기중")
        val startedId = createTournament(mockMvc, "진행중")
        addItemsToTournament(mockMvc, startedId, userId, saveWishItem(), saveWishItem())
        mockMvc.perform(
            post("/api/v1/tournaments/$startedId/start")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
        )

        mockMvc
            .perform(
                get("/api/v1/tournaments")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                    .param("status", "PENDING"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].tournamentId").value(pendingId))
            .andExpect(jsonPath("$.data[0].participantProfileImages[0]").value(userProfileImage))
    }

    @Test
    fun `GET tournaments 에서 복수 status 필터를 지정하면 해당 상태들만 반환된다`() {
        val mockMvc = buildMockMvc()
        val pendingId = createTournament(mockMvc, "대기중")
        val startedId = createTournament(mockMvc, "진행중")
        addItemsToTournament(mockMvc, startedId, userId, saveWishItem(), saveWishItem())
        mockMvc.perform(
            post("/api/v1/tournaments/$startedId/start")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
        )

        mockMvc
            .perform(
                get("/api/v1/tournaments")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                    .param("status", "PENDING", "IN_PROGRESS"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[?(@.tournamentId == $pendingId)]").exists())
            .andExpect(jsonPath("$.data[?(@.tournamentId == $startedId)]").exists())
    }

    @Test
    fun `GET tournaments 에서 status=IN_PROGRESS 로 조회하면 COMPLETED 토너먼트는 포함되지 않는다`() {
        val mockMvc = buildMockMvc()
        val (completedId, _, _) = completeTournamentWith2Items(mockMvc)
        val inProgressId = createTournament(mockMvc, "진행중")
        addItemsToTournament(mockMvc, inProgressId, userId, saveWishItem(), saveWishItem())
        mockMvc.perform(
            post("/api/v1/tournaments/$inProgressId/start")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
        )

        mockMvc
            .perform(
                get("/api/v1/tournaments")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                    .param("status", "IN_PROGRESS"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].tournamentId").value(inProgressId))
            .andExpect(jsonPath("$.data[?(@.tournamentId == $completedId)]").doesNotExist())
    }

    @Test
    fun `GET tournaments 에서 토너먼트가 없으면 빈 배열을 반환한다`() {
        val mockMvc = buildMockMvc()

        mockMvc
            .perform(
                get("/api/v1/tournaments")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(0))
    }

    @Test
    fun `GET tournaments 에서 인증 없이 요청하면 401 을 반환한다`() {
        val mockMvc = buildMockMvc()

        mockMvc
            .perform(get("/api/v1/tournaments"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `GET tournaments 에서 limit 을 주면 최근순 상위 N개만 반환한다`() {
        val mockMvc = buildMockMvc()
        createTournament(mockMvc, "토너먼트1")
        val second = createTournament(mockMvc, "토너먼트2")
        val third = createTournament(mockMvc, "토너먼트3") // 가장 최근

        // limit 없으면 전체
        mockMvc
            .perform(
                get("/api/v1/tournaments")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(3))

        // limit=2 면 최근순 상위 2개(3번, 2번)만, 가장 오래된 1번은 제외
        mockMvc
            .perform(
                get("/api/v1/tournaments")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                    .param("limit", "2"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].tournamentId").value(third))
            .andExpect(jsonPath("$.data[1].tournamentId").value(second))
    }

    @Test
    fun `GET tournaments 는 최근 등록 아이템의 이미지 최대 2장을 thumbnailUrls 에 최근순으로 담는다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc, "썸네일 토너먼트")
        // 오래된 → 최신 순으로 3개 등록 (tournament_item id 가 증가 = 최근). 최근 2장만 최근순으로 담겨야 한다.
        saveTournamentItemFor(tournamentId, itemJpaRepository.save(Item()), imageUrl = "https://img.example.com/1.jpg")
        saveTournamentItemFor(tournamentId, itemJpaRepository.save(Item()), imageUrl = "https://img.example.com/2.jpg")
        saveTournamentItemFor(tournamentId, itemJpaRepository.save(Item()), imageUrl = "https://img.example.com/3.jpg")

        mockMvc
            .perform(
                get("/api/v1/tournaments")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].thumbnailUrls.length()").value(2))
            .andExpect(jsonPath("$.data[0].thumbnailUrls[0]").value("https://img.example.com/3.jpg"))
            .andExpect(jsonPath("$.data[0].thumbnailUrls[1]").value("https://img.example.com/2.jpg"))
    }

    @Test
    fun `GET tournaments 에서 이미지 준비된 아이템이 없으면 thumbnailUrls 는 빈 배열이다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc, "미준비 토너먼트")
        // 아직 파싱 중(PROCESSING)이라 이미지가 없는 아이템만 있는 경우
        saveTournamentItemFor(
            tournamentId,
            itemJpaRepository.save(Item()),
            status = ItemStatus.PROCESSING,
            imageUrl = null,
        )

        mockMvc
            .perform(
                get("/api/v1/tournaments")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].thumbnailUrls").isArray)
            .andExpect(jsonPath("$.data[0].thumbnailUrls.length()").value(0))
    }

    @Test
    fun `GET tournaments 에서 limit 이 1 미만이면 400 을 반환한다`() {
        val mockMvc = buildMockMvc()

        for (limit in listOf("0", "-1")) {
            mockMvc
                .perform(
                    get("/api/v1/tournaments")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                        .param("limit", limit),
                ).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("TOURNAMENT-033"))
                .andExpect(jsonPath("$.detail").value("조회 개수는 1 이상이어야 해요."))
        }
    }

    @Test
    fun `GET tournaments 에서 limit 이 정수가 아니면 400 을 반환한다`() {
        val mockMvc = buildMockMvc()

        mockMvc
            .perform(
                get("/api/v1/tournaments")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                    .param("limit", "abc"),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `GET tournaments 에서 FAILED 스냅샷의 이미지는 thumbnailUrls 에서 제외된다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc, "FAILED 이미지 토너먼트")
        // 추출 실패(FAILED)했지만 imageUrl 이 남아있는 stale 스냅샷 — 카드에 노출되면 안 된다.
        saveTournamentItemFor(
            tournamentId,
            itemJpaRepository.save(Item()),
            status = ItemStatus.FAILED,
            imageUrl = "https://img.example.com/stale.jpg",
        )

        mockMvc
            .perform(
                get("/api/v1/tournaments")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].thumbnailUrls.length()").value(0))
    }

    @Test
    fun `GET tournaments 에서 CLONE 토너먼트는 원본 ROOT 아이템의 썸네일을 사용한다`() {
        val mockMvc = buildMockMvc()
        // ROOT 는 다른 유저 소유라 내 목록엔 안 뜬다. 아이템(이미지)은 ROOT 에 붙는다.
        val root =
            tournamentJpaRepository.save(
                Tournament(
                    ownerTournamentUserId = 1,
                    name = "원본 ROOT",
                    inviteCode = "ROOT01",
                    inviteExpiresAt = LocalDateTime.now().plusDays(1),
                ),
            )
        saveTournamentItemFor(root.getId(), itemJpaRepository.save(Item()), imageUrl = "https://img.example.com/root1.jpg")
        saveTournamentItemFor(root.getId(), itemJpaRepository.save(Item()), imageUrl = "https://img.example.com/root2.jpg")

        // CLONE 은 자기 아이템 없이 sourceTournamentId 로 ROOT 를 가리킨다. 내가 멤버라 목록에 뜬다.
        val clone =
            tournamentJpaRepository.save(
                Tournament(
                    ownerTournamentUserId = 2,
                    name = "내 CLONE",
                    inviteCode = "CLON01",
                    inviteExpiresAt = LocalDateTime.now().plusDays(1),
                    sourceTournamentId = root.getId(),
                ),
            )
        tournamentUserJpaRepository.save(TournamentUser(tournamentId = clone.getId(), userId = userId))

        mockMvc
            .perform(
                get("/api/v1/tournaments")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].tournamentId").value(clone.getId()))
            .andExpect(jsonPath("$.data[0].thumbnailUrls.length()").value(2))
            .andExpect(jsonPath("$.data[0].thumbnailUrls[0]").value("https://img.example.com/root2.jpg"))
            .andExpect(jsonPath("$.data[0].thumbnailUrls[1]").value("https://img.example.com/root1.jpg"))
    }

    @Test
    fun `GET tournaments 에서 멤버로 참여한 ROOT 는 PENDING 까지만 보이고 시작 후엔 목록에서 빠진다`() {
        val mockMvc = buildMockMvc()
        saveUser(otherUserId, "https://cdn.example.com/other.jpg", "다른유저")

        // userId 소유 ROOT 에 otherUserId 가 소셜 참여
        val rootTournamentId = createTournament(mockMvc)
        mockMvc.perform(
            post("/api/v1/tournaments/$rootTournamentId/join")
                .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"inviteCode":null}"""),
        )

        // PENDING 동안엔 멤버 목록에도 보인다
        mockMvc
            .perform(
                get("/api/v1/tournaments")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].tournamentId").value(rootTournamentId))

        // 소유자가 start → ROOT IN_PROGRESS 전환
        addItemsToTournament(mockMvc, rootTournamentId, userId, saveWishItem(), saveWishItem())
        mockMvc.perform(
            post("/api/v1/tournaments/$rootTournamentId/start")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
        )

        // 시작 후 멤버 목록에선 빠진다 (멤버는 본인 CLONE 으로 플레이·표시)
        mockMvc
            .perform(
                get("/api/v1/tournaments")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(0))

        // 소유자 목록엔 계속 보인다
        mockMvc
            .perform(
                get("/api/v1/tournaments")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].tournamentId").value(rootTournamentId))
    }

    @Test
    fun `GET tournaments-id 는 COMPLETED 토너먼트에서 1위부터 4위까지 순위 결과를 반환한다`() {
        val mockMvc = buildMockMvc()
        val item1Id = saveWishItem(name = "1위아이템", price = 10_000)
        val item2Id = saveWishItem(name = "2위아이템", price = 20_000)
        val item3Id = saveWishItem(name = "3위아이템", price = 30_000)
        val item4Id = saveWishItem(name = "4위아이템", price = 40_000)
        val tournamentId = createTournament(mockMvc)
        addItemsToTournament(mockMvc, tournamentId, userId, item1Id, item2Id, item3Id, item4Id)
        mockMvc.perform(
            post("/api/v1/tournaments/$tournamentId/start")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
        )
        // start 는 가격 오름차순 반환 → ti1~ti4 순 — 클라이언트 페어링: [0]vs[1], [2]vs[3]
        val items = tournamentItemJpaRepository.findAllByTournamentIdAndNotDeleted(tournamentId)
        val ti1 = items[0].getId()
        val ti2 = items[1].getId()
        val ti3 = items[2].getId()
        val ti4 = items[3].getId()

        // round-4: ti1 vs ti2 → ti1 승, ti3 vs ti4 → ti3 승
        mockMvc.perform(
            post("/api/v1/tournaments/$tournamentId/matches")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentRound":4,"firstTournamentItemId":$ti1,"secondTournamentItemId":$ti2,"selectedTournamentItemId":$ti1}"""),
        ).andExpect(status().isOk)
        mockMvc.perform(
            post("/api/v1/tournaments/$tournamentId/matches")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentRound":4,"firstTournamentItemId":$ti3,"secondTournamentItemId":$ti4,"selectedTournamentItemId":$ti3}"""),
        ).andExpect(status().isOk)
        // round-2 결승: ti1 vs ti3 → ti1 승
        mockMvc.perform(
            post("/api/v1/tournaments/$tournamentId/matches")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentRound":2,"firstTournamentItemId":$ti1,"secondTournamentItemId":$ti3,"selectedTournamentItemId":$ti1}"""),
        ).andExpect(status().isOk)

        mockMvc
            .perform(
                get("/api/v1/tournaments/$tournamentId")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("COMPLETED"))
            .andExpect(jsonPath("$.data.completed.result.length()").value(4))
            // 1위: ti1, 2위: ti3(결승 패배), 3위: ti2(준결승 패배, tiId 낮음), 4위: ti4
            .andExpect(jsonPath("$.data.completed.result[0].rank").value(1))
            .andExpect(jsonPath("$.data.completed.result[0].tournamentItemId").value(ti1))
            .andExpect(jsonPath("$.data.completed.result[0].name").value("1위아이템"))
            .andExpect(jsonPath("$.data.completed.result[1].rank").value(2))
            .andExpect(jsonPath("$.data.completed.result[1].tournamentItemId").value(ti3))
            .andExpect(jsonPath("$.data.completed.result[2].rank").value(3))
            .andExpect(jsonPath("$.data.completed.result[2].tournamentItemId").value(ti2))
            .andExpect(jsonPath("$.data.completed.result[3].rank").value(4))
            .andExpect(jsonPath("$.data.completed.result[3].tournamentItemId").value(ti4))
            .andExpect(jsonPath("$.data.pending").doesNotExist())
            .andExpect(jsonPath("$.data.inProgress").doesNotExist())
    }

    @Test
    fun `GET tournaments-id 는 2개 아이템 COMPLETED 토너먼트에서 준결승 없이 1위와 2위만 반환한다`() {
        val mockMvc = buildMockMvc()
        val (tournamentId, ti1, ti2) = startTournamentWith2Items(mockMvc)
        // 결승만 치름 — 준결승 히스토리 없음
        mockMvc.perform(
            post("/api/v1/tournaments/$tournamentId/matches")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentRound":2,"firstTournamentItemId":$ti1,"secondTournamentItemId":$ti2,"selectedTournamentItemId":$ti1}"""),
        ).andExpect(status().isOk)

        mockMvc
            .perform(
                get("/api/v1/tournaments/$tournamentId")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("COMPLETED"))
            .andExpect(jsonPath("$.data.completed.result.length()").value(2))
            .andExpect(jsonPath("$.data.completed.result[0].rank").value(1))
            .andExpect(jsonPath("$.data.completed.result[0].tournamentItemId").value(ti1))
            .andExpect(jsonPath("$.data.completed.result[1].rank").value(2))
            .andExpect(jsonPath("$.data.completed.result[1].tournamentItemId").value(ti2))
            .andExpect(jsonPath("$.data.pending").doesNotExist())
            .andExpect(jsonPath("$.data.inProgress").doesNotExist())
    }

    @Test
    fun `GET tournaments-id 는 32개 아이템 토너먼트에서 6번 선택 후 현재 라운드 미대결 생존 아이템 20개를 가격 오름차순으로 반환한다`() {
        val mockMvc = buildMockMvc()
        // 가격 1_000 ~ 32_000 순으로 32개 아이템 생성 (삽입 순서 = 가격 오름차순)
        val itemIds = (1..32).map { i -> saveWishItem(name = "아이템$i", price = i * 1_000) }.toLongArray()
        val tournamentId = createTournament(mockMvc)
        addItemsToTournament(mockMvc, tournamentId, userId, *itemIds)
        mockMvc.perform(
            post("/api/v1/tournaments/$tournamentId/start")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
        )

        val allItems = tournamentItemJpaRepository.findAllByTournamentIdAndNotDeleted(tournamentId)
        // 6번의 매치 기록 — 1라운드(currentRound=32), 각 2개씩 소진 → ti[0]~ti[11] 등장
        repeat(6) { i ->
            val first = allItems[i * 2].getId()
            val second = allItems[i * 2 + 1].getId()
            mockMvc.perform(
                post("/api/v1/tournaments/$tournamentId/matches")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"currentRound":32,"firstTournamentItemId":$first,"secondTournamentItemId":$second,"selectedTournamentItemId":$first}"""),
            ).andExpect(status().isOk)
        }

        val lastFirst = allItems[10].getId()
        val lastSecond = allItems[11].getId()

        mockMvc
            .perform(
                get("/api/v1/tournaments/$tournamentId")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.data.inProgress.currentRound").value(32))
            // 마지막 히스토리 = 6번째 매치
            .andExpect(jsonPath("$.data.inProgress.lastHistory.currentRound").value(32))
            .andExpect(jsonPath("$.data.inProgress.lastHistory.firstTournamentItemId").value(lastFirst))
            .andExpect(jsonPath("$.data.inProgress.lastHistory.secondTournamentItemId").value(lastSecond))
            .andExpect(jsonPath("$.data.inProgress.lastHistory.selectedTournamentItemId").value(lastFirst))
            // round-32 미대결 생존 아이템 20개(ti[12]~ti[31]) 가격 오름차순
            .andExpect(jsonPath("$.data.inProgress.remainingItems.length()").value(20))
            .andExpect(jsonPath("$.data.inProgress.remainingItems[0].price").value(13_000))
            .andExpect(jsonPath("$.data.inProgress.remainingItems[19].price").value(32_000))
    }

    @Test
    fun `GET tournaments-id 는 IN_PROGRESS 토너먼트에서 마지막 히스토리와 현재 라운드 미대결 생존 아이템을 가격 오름차순으로 반환한다`() {
        val mockMvc = buildMockMvc()
        val item1Id = saveWishItem(name = "아이템1", price = 10_000)
        val item2Id = saveWishItem(name = "아이템2", price = 40_000)
        val item3Id = saveWishItem(name = "아이템3", price = 20_000)
        val item4Id = saveWishItem(name = "아이템4", price = 30_000)
        val tournamentId = createTournament(mockMvc)
        addItemsToTournament(mockMvc, tournamentId, userId, item1Id, item2Id, item3Id, item4Id)
        mockMvc.perform(
            post("/api/v1/tournaments/$tournamentId/start")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
        )
        val items = tournamentItemJpaRepository.findAllByTournamentIdAndNotDeleted(tournamentId)
        val ti1 = items[0].getId()
        val ti2 = items[1].getId()

        mockMvc.perform(
            post("/api/v1/tournaments/$tournamentId/matches")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentRound":4,"firstTournamentItemId":$ti1,"secondTournamentItemId":$ti2,"selectedTournamentItemId":$ti1}"""),
        )

        mockMvc
            .perform(
                get("/api/v1/tournaments/$tournamentId")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.data.pending").doesNotExist())
            .andExpect(jsonPath("$.data.inProgress.currentRound").value(4))
            .andExpect(jsonPath("$.data.inProgress.lastHistory.currentRound").value(4))
            .andExpect(jsonPath("$.data.inProgress.lastHistory.firstTournamentItemId").value(ti1))
            .andExpect(jsonPath("$.data.inProgress.lastHistory.secondTournamentItemId").value(ti2))
            .andExpect(jsonPath("$.data.inProgress.lastHistory.selectedTournamentItemId").value(ti1))
            // round-4 미대결 생존 아이템 2개(item3, item4) 가격 오름차순
            .andExpect(jsonPath("$.data.inProgress.remainingItems.length()").value(2))
            .andExpect(jsonPath("$.data.inProgress.remainingItems[0].price").value(20_000))
            .andExpect(jsonPath("$.data.inProgress.remainingItems[1].price").value(30_000))
    }

    @Test
    fun `GET tournaments-id 는 IN_PROGRESS 토너먼트에서 매치 기록이 없으면 lastHistory 가 없고 전체 아이템을 가격 오름차순으로 반환한다`() {
        val mockMvc = buildMockMvc()
        val item1Id = saveWishItem(name = "아이템1", price = 30_000)
        val item2Id = saveWishItem(name = "아이템2", price = 10_000)
        val tournamentId = createTournament(mockMvc)
        addItemsToTournament(mockMvc, tournamentId, userId, item1Id, item2Id)
        mockMvc.perform(
            post("/api/v1/tournaments/$tournamentId/start")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
        )

        mockMvc
            .perform(
                get("/api/v1/tournaments/$tournamentId")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.data.inProgress.currentRound").value(2))
            .andExpect(jsonPath("$.data.inProgress.lastHistory").doesNotExist())
            .andExpect(jsonPath("$.data.inProgress.remainingItems.length()").value(2))
            .andExpect(jsonPath("$.data.inProgress.remainingItems[0].price").value(10_000))
            .andExpect(jsonPath("$.data.inProgress.remainingItems[1].price").value(30_000))
    }

    @Test
    fun `GET tournaments-id 는 PENDING 토너먼트의 아이템 목록을 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)
        addItemsToTournament(mockMvc, tournamentId, userId, saveWishItem(), saveWishItem())

        mockMvc
            .perform(
                get("/api/v1/tournaments/$tournamentId")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("PENDING"))
            .andExpect(jsonPath("$.data.pending.items.length()").value(2))
            .andExpect(jsonPath("$.data.inProgress").doesNotExist())
            .andExpect(jsonPath("$.data.completed").doesNotExist())
    }

    @Test
    fun `GET tournaments-id 는 PENDING 상태에서 READY 아이템의 status 가 READY 로 내려온다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)
        val itemId = saveWishItem(name = "나이키 에어맥스", price = 99_000)
        addItemsToTournament(mockMvc, tournamentId, userId, itemId)

        mockMvc
            .perform(
                get("/api/v1/tournaments/$tournamentId")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.pending.items[0].status").value("READY"))
            .andExpect(jsonPath("$.data.pending.items[0].name").value("나이키 에어맥스"))
            .andExpect(jsonPath("$.data.pending.items[0].price").value(99_000))
            .andExpect(jsonPath("$.data.pending.items[0].tournamentItemId").isNumber)
            .andExpect(jsonPath("$.data.pending.items[0].itemId").value(itemId))
            .andExpect(jsonPath("$.data.pending.items[0].userId").value(userId.toString()))
    }

    @Test
    fun `GET tournaments-id 는 PENDING 상태에서 PROCESSING 아이템이 status=PROCESSING 으로 목록에 포함되고 name·price·imageUrl 은 없다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)
        val processingItem = itemJpaRepository.save(Item())
        saveTournamentItemFor(tournamentId, processingItem, status = ItemStatus.PROCESSING)

        mockMvc
            .perform(
                get("/api/v1/tournaments/$tournamentId")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("PENDING"))
            .andExpect(jsonPath("$.data.pending.items.length()").value(1))
            .andExpect(jsonPath("$.data.pending.items[0].status").value("PROCESSING"))
            .andExpect(jsonPath("$.data.pending.items[0].itemId").value(processingItem.getId()))
            .andExpect(jsonPath("$.data.pending.items[0].name").doesNotExist())
            .andExpect(jsonPath("$.data.pending.items[0].price").doesNotExist())
            .andExpect(jsonPath("$.data.pending.items[0].imageUrl").doesNotExist())
    }

    @Test
    fun `GET tournaments-id 는 IN_PROGRESS 상태에서 remainingItems 의 각 아이템에 status 가 포함된다`() {
        val mockMvc = buildMockMvc()
        val item1Id = saveWishItem(name = "아이템1", price = 10_000)
        val item2Id = saveWishItem(name = "아이템2", price = 20_000)
        val tournamentId = createTournament(mockMvc)
        addItemsToTournament(mockMvc, tournamentId, userId, item1Id, item2Id)
        mockMvc.perform(
            post("/api/v1/tournaments/$tournamentId/start")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
        )

        mockMvc
            .perform(
                get("/api/v1/tournaments/$tournamentId")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
            .andExpect(jsonPath("$.data.inProgress.remainingItems.length()").value(2))
            .andExpect(jsonPath("$.data.inProgress.remainingItems[0].status").value("READY"))
            .andExpect(jsonPath("$.data.inProgress.remainingItems[1].status").value("READY"))
    }

    @Test
    fun `GET tournaments-id 는 PENDING 토너먼트 응답에 참여자 정보를 포함한다`() {
        val mockMvc = buildMockMvc()
        saveUser(userId, userProfileImage)
        val tournamentId = createTournament(mockMvc)

        mockMvc
            .perform(
                get("/api/v1/tournaments/$tournamentId")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("PENDING"))
            .andExpect(jsonPath("$.data.pending.participants").isArray)
            .andExpect(jsonPath("$.data.pending.participants.length()").value(1))
            .andExpect(jsonPath("$.data.pending.participants[0].userId").isString)
            .andExpect(jsonPath("$.data.pending.participants[0].nickname").isString)
            .andExpect(jsonPath("$.data.pending.participants[0].profileImage").value(userProfileImage))
    }

    @Test
    fun `GET tournaments-id 에서 참가자가 아니면 403 을 반환한다`() {
        val mockMvc = buildMockMvc()
        val (tournamentId) = startTournamentWith2Items(mockMvc)

        mockMvc
            .perform(
                get("/api/v1/tournaments/$tournamentId")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId)),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `GET tournaments-id 는 CLONE 단건 조회 시 pending items 에 ROOT 아이템을 해소해 내려준다`() {
        val mockMvc = buildMockMvc()
        saveUser(otherUserId, "https://cdn.example.com/other.jpg", "다른유저")
        val (tournamentId, _, _) = completeTournamentWith2Items(mockMvc)
        mockMvc.perform(
            post("/api/v1/tournaments/$tournamentId/play-link")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
        val cloneResult = mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/from-play-link")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId)),
            ).andReturn()
        val cloneId = objectMapper.readTree(cloneResult.response.contentAsString)["data"].asLong()

        // CLONE 은 DB 아이템이 없지만 ROOT 의 2개 아이템을 해소해 내려줘야 한다.
        mockMvc
            .perform(
                get("/api/v1/tournaments/$cloneId")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("PENDING"))
            .andExpect(jsonPath("$.data.pending.items.length()").value(2))
    }

    @Test
    fun `GET tournaments-id 는 ROOT 가 COMPLETED 여도 아직 시작 안 한 참여자에게 403 대신 시작 가능 상태를 준다`() {
        val mockMvc = buildMockMvc()
        saveUser(otherUserId, "https://cdn.example.com/other.jpg", "다른유저")
        val rootTournamentId = createTournament(mockMvc)
        // otherUserId 가 멤버로 참여 (초대 코드 없이 링크 직접 접근)
        mockMvc.perform(
            post("/api/v1/tournaments/$rootTournamentId/join")
                .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"inviteCode":null}"""),
        )
        addItemsToTournament(mockMvc, rootTournamentId, userId, saveWishItem(name = "소셜1"), saveWishItem(name = "소셜2"))
        mockMvc.perform(
            post("/api/v1/tournaments/$rootTournamentId/start")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
        )
        val rootItems = tournamentItemJpaRepository.findAllByTournamentIdAndNotDeleted(rootTournamentId)
        // owner 가 결승까지 끝내 ROOT 즉시 COMPLETED
        mockMvc.perform(
            post("/api/v1/tournaments/$rootTournamentId/matches")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"currentRound":2,"firstTournamentItemId":${rootItems[0].getId()},"secondTournamentItemId":${rootItems[1].getId()},"selectedTournamentItemId":${rootItems[0].getId()}}""",
                ),
        )

        // 아직 본인 CLONE 을 시작 안 한 멤버가 ROOT 조회 → 403 아니라 시작 가능 상태(ownerStarted)
        mockMvc
            .perform(
                get("/api/v1/tournaments/$rootTournamentId")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.pending.ownerStarted").value(true))
            .andExpect(jsonPath("$.data.pending.items.length()").value(2))

        // 그리고 멤버는 본인 CLONE 을 만들어 진행할 수 있다 (ROOT 가 COMPLETED 여도)
        mockMvc
            .perform(
                post("/api/v1/tournaments/$rootTournamentId/start")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId)),
            ).andExpect(status().isOk)
    }

    @Test
    fun `GET tournaments-id 에서 존재하지 않는 tournamentId 이면 404 를 반환한다`() {
        val mockMvc = buildMockMvc()

        mockMvc
            .perform(
                get("/api/v1/tournaments/999999")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isNotFound)
    }

    @Test
    fun `GET tournaments-id 는 ROOT 토너먼트 응답에 sourceTournamentId 가 없다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)

        mockMvc
            .perform(
                get("/api/v1/tournaments/$tournamentId")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.sourceTournamentId").doesNotExist())
    }

    @Test
    fun `GET tournaments-id 는 CLONE 토너먼트 응답에 sourceTournamentId 로 ROOT id 를 내려준다`() {
        val mockMvc = buildMockMvc()
        saveUser(otherUserId, "https://cdn.example.com/guest.jpg", "게스트")
        val (rootId) = completeTournamentWith2Items(mockMvc)
        mockMvc.perform(
            post("/api/v1/tournaments/$rootId/play-link")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
        val cloneResult = mockMvc.perform(
            post("/api/v1/tournaments/$rootId/from-play-link")
                .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId)),
        ).andReturn()
        val cloneId = objectMapper.readTree(cloneResult.response.contentAsString)["data"].asLong()

        mockMvc
            .perform(
                get("/api/v1/tournaments/$cloneId")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.sourceTournamentId").value(rootId))
    }

    @Test
    fun `GET tournaments-id 에서 ROOT 소유자의 COMPLETED 응답에 canAddItem=true 가 포함된다`() {
        val mockMvc = buildMockMvc()
        val (tournamentId) = completeTournamentWith2Items(mockMvc)

        mockMvc
            .perform(
                get("/api/v1/tournaments/$tournamentId")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.completed.canAddItem").value(true))
    }

    @Test
    fun `GET tournaments-id 에서 플레이링크 CLONE 소유자의 COMPLETED 응답에 canAddItem=false 가 포함된다`() {
        val mockMvc = buildMockMvc()
        saveUser(otherUserId, "https://cdn.example.com/guest.jpg", "게스트")
        val (rootId, ti1, ti2) = completeTournamentWith2Items(mockMvc)
        mockMvc.perform(
            post("/api/v1/tournaments/$rootId/play-link")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
        val cloneResult = mockMvc.perform(
            post("/api/v1/tournaments/$rootId/from-play-link")
                .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId)),
        ).andReturn()
        val cloneId = objectMapper.readTree(cloneResult.response.contentAsString)["data"].asLong()
        mockMvc.perform(
            post("/api/v1/tournaments/$cloneId/start")
                .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId)),
        )
        mockMvc.perform(
            post("/api/v1/tournaments/$cloneId/matches")
                .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentRound":2,"firstTournamentItemId":$ti1,"secondTournamentItemId":$ti2,"selectedTournamentItemId":$ti1}"""),
        )

        mockMvc
            .perform(
                get("/api/v1/tournaments/$cloneId")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.completed.canAddItem").value(false))
    }

    @Test
    fun `GET tournaments-id 에서 소셜 초대 CLONE 소유자의 COMPLETED 응답에 canAddItem=true 가 포함된다`() {
        val mockMvc = buildMockMvc()
        saveUser(otherUserId, "https://cdn.example.com/other.jpg", "다른유저")

        // ROOT 생성 + otherUserId 소셜 참여
        val rootTournamentId = createTournament(mockMvc)
        mockMvc.perform(
            post("/api/v1/tournaments/$rootTournamentId/join")
                .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"inviteCode":null}"""),
        )

        // 아이템 추가 후 소유자 start (ROOT IN_PROGRESS 전환)
        addItemsToTournament(mockMvc, rootTournamentId, userId, saveWishItem(name = "소셜1"), saveWishItem(name = "소셜2"))
        mockMvc.perform(
            post("/api/v1/tournaments/$rootTournamentId/start")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
        )

        // otherUserId start → CLONE 생성 + 시작 (response 에 cloneId·items 포함)
        val startResult = mockMvc.perform(
            post("/api/v1/tournaments/$rootTournamentId/start")
                .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId)),
        ).andReturn()
        val startData = objectMapper.readTree(startResult.response.contentAsString)["data"]
        val cloneId = startData["tournamentId"].asLong()
        val cloneItems = startData["items"]
        val cloneTi1 = cloneItems[0]["tournamentItemId"].asLong()
        val cloneTi2 = cloneItems[1]["tournamentItemId"].asLong()

        // CLONE 결승 완료
        mockMvc.perform(
            post("/api/v1/tournaments/$cloneId/matches")
                .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentRound":2,"firstTournamentItemId":$cloneTi1,"secondTournamentItemId":$cloneTi2,"selectedTournamentItemId":$cloneTi1}"""),
        )

        mockMvc
            .perform(
                get("/api/v1/tournaments/$cloneId")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.completed.canAddItem").value(true))
    }

    @Test
    fun `DELETE tournaments-id-items-itemId 는 PENDING 토너먼트에서 아이템을 삭제하고 200 을 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)
        addItemsToTournament(mockMvc, tournamentId, userId, saveWishItem(), saveWishItem())
        val itemId = tournamentItemJpaRepository.findAllByTournamentIdAndNotDeleted(tournamentId).first().getId()

        mockMvc
            .perform(
                delete("/api/v1/tournaments/$tournamentId/items/$itemId")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)

        val remaining = tournamentItemJpaRepository.findAllByTournamentIdAndNotDeleted(tournamentId)
        assertEquals(1, remaining.size)
        assertTrue(remaining.none { it.getId() == itemId })
    }

    @Test
    fun `DELETE tournaments-id-items-itemId 에서 IN_PROGRESS 토너먼트이면 409 를 반환한다`() {
        val mockMvc = buildMockMvc()
        val (tournamentId, item1Id) = startTournamentWith2Items(mockMvc)

        mockMvc
            .perform(
                delete("/api/v1/tournaments/$tournamentId/items/$item1Id")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isConflict)
    }

    @Test
    fun `DELETE tournaments-id-items-itemId 에서 아이템 추가자가 아니고 소유자도 아니면 403 을 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)
        addItemsToTournament(mockMvc, tournamentId, userId, saveWishItem(), saveWishItem())
        val itemId = tournamentItemJpaRepository.findAllByTournamentIdAndNotDeleted(tournamentId).first().getId()

        mockMvc
            .perform(
                delete("/api/v1/tournaments/$tournamentId/items/$itemId")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId)),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `DELETE tournaments-id-items-itemId 에서 소유자는 다른 참가자가 추가한 아이템도 삭제할 수 있다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)
        val otherItem = saveWishItem(otherUserId)
        tournamentUserJpaRepository.save(TournamentUser(tournamentId = tournamentId, userId = otherUserId))
        // 위시 추가는 소유자 전용이므로 DB에 직접 삽입해 다른 유저가 추가한 상황을 구성. 출전 시점 고정 snapshot 을 함께 시딩한다(NOT NULL).
        val otherSnapshot = saveSnapshot(otherItem, status = ItemStatus.READY)
        tournamentItemJpaRepository.save(TournamentItem(tournamentId = tournamentId, userId = otherUserId, snapshotId = otherSnapshot.getId()))
        val itemId = tournamentItemJpaRepository.findAllByTournamentIdAndNotDeleted(tournamentId).first().getId()

        mockMvc
            .perform(
                delete("/api/v1/tournaments/$tournamentId/items/$itemId")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)

        assertTrue(
            tournamentItemJpaRepository.findAllByTournamentIdAndNotDeleted(tournamentId).none { it.getId() == itemId },
        )
    }

    @Test
    fun `DELETE tournaments-id-items-itemId 에서 존재하지 않는 아이템이면 404 를 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)

        mockMvc
            .perform(
                delete("/api/v1/tournaments/$tournamentId/items/999999")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isNotFound)
    }

    @Test
    fun `DELETE tournaments-id-items-itemId 에서 다른 토너먼트 소속 아이템이면 404 를 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId1 = createTournament(mockMvc, "토너먼트1")
        val tournamentId2 = createTournament(mockMvc, "토너먼트2")
        // 토너먼트 소속만 검증하는 시나리오라 표시값은 무관하다. 출전 시점 고정 snapshot 만 시딩해 연결한다(NOT NULL).
        val snapshotOfTournament2 = saveSnapshot(999L, status = ItemStatus.READY)
        val itemOfTournament2 =
            tournamentItemJpaRepository
                .save(
                    TournamentItem(tournamentId = tournamentId2, userId = userId, snapshotId = snapshotOfTournament2.getId()),
                ).getId()

        mockMvc
            .perform(
                delete("/api/v1/tournaments/$tournamentId1/items/$itemOfTournament2")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isNotFound)
    }

    @Test
    fun `PATCH tournaments-id-items-itemId 는 FAILED 아이템을 수정하고 READY 로 전환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)
        val failedItem = itemJpaRepository.save(Item())
        // imageUrl 있는 FAILED — recover 시 imageUrl 파라미터를 보내지 않아도 apply 가 기존 값을 유지해 READY 불변식 통과
        val snapshot = saveSnapshot(failedItem.getId(), status = ItemStatus.FAILED, imageUrl = "https://img.example.com/a.png")
        tournamentItemJpaRepository.save(
            TournamentItem(tournamentId = tournamentId, userId = userId, snapshotId = snapshot.getId()),
        )
        val tournamentItemId = tournamentItemJpaRepository.findAllByTournamentIdAndNotDeleted(tournamentId).first().getId()

        mockMvc
            .perform(
                multipart(HttpMethod.PATCH, "/api/v1/tournaments/$tournamentId/items/$tournamentItemId")
                    .param("name", "수정된 이름")
                    .param("price", "50000")
                    .param("currency", "KRW")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)

        // 보정 대상은 tournament_item 이 가리키는 고정 snapshot 이다 — 표시값·상태는 그 snapshot 에서 읽는다(4a).
        val updated = itemSnapshotJpaRepository.findById(snapshot.getId()).get()
        assertEquals("수정된 이름", updated.name)
        assertEquals(50000, updated.currentPrice)
        assertEquals("KRW", updated.currency)
        assertEquals(ItemStatus.READY, updated.status)
    }

    @Test
    fun `PATCH tournaments-id-items-itemId 에서 READY 아이템이면 409 를 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)
        val readyItemId = saveWishItem()
        addItemsToTournament(mockMvc, tournamentId, userId, readyItemId)
        val tournamentItemId = tournamentItemJpaRepository.findAllByTournamentIdAndNotDeleted(tournamentId).first().getId()

        mockMvc
            .perform(
                multipart(HttpMethod.PATCH, "/api/v1/tournaments/$tournamentId/items/$tournamentItemId")
                    .param("name", "수정 시도")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isConflict)
    }

    @Test
    fun `PATCH tournaments-id-items-itemId 에서 PROCESSING 아이템이면 409 를 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)
        val processingItem = itemJpaRepository.save(Item())
        val snapshot = saveSnapshot(processingItem.getId(), status = ItemStatus.PROCESSING)
        tournamentItemJpaRepository.save(
            TournamentItem(tournamentId = tournamentId, userId = userId, snapshotId = snapshot.getId()),
        )
        val tournamentItemId = tournamentItemJpaRepository.findAllByTournamentIdAndNotDeleted(tournamentId).first().getId()

        mockMvc
            .perform(
                multipart(HttpMethod.PATCH, "/api/v1/tournaments/$tournamentId/items/$tournamentItemId")
                    .param("name", "수정 시도")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isConflict)
    }

    @Test
    fun `PATCH tournaments-id-items-itemId 에서 IN_PROGRESS 토너먼트이면 409 를 반환한다`() {
        val mockMvc = buildMockMvc()
        val (tournamentId, item1Id) = startTournamentWith2Items(mockMvc)
        // 토너먼트가 IN_PROGRESS 라 snapshot 검증 전 notPending(409)에서 막힌다 — 고정 snapshot 만 시딩해 연결하면 된다(NOT NULL).
        val failedItem = itemJpaRepository.save(Item())
        val snapshot = saveSnapshot(failedItem.getId(), status = ItemStatus.FAILED)
        tournamentItemJpaRepository.save(TournamentItem(tournamentId = tournamentId, userId = userId, snapshotId = snapshot.getId()))
        val tournamentItemId = tournamentItemJpaRepository.findAllByTournamentIdAndNotDeleted(tournamentId).last().getId()

        mockMvc
            .perform(
                multipart(HttpMethod.PATCH, "/api/v1/tournaments/$tournamentId/items/$tournamentItemId")
                    .param("name", "수정 시도")
                    .param("price", "10000")
                    .param("currency", "KRW")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isConflict)
    }

    @Test
    fun `PATCH tournaments-id-items-itemId 에서 아이템 등록자가 아니면 403 을 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)
        tournamentUserJpaRepository.save(TournamentUser(tournamentId = tournamentId, userId = otherUserId))
        // 등록자(otherUserId)가 아닌 userId 의 PATCH 라 snapshot 검증 전 forbidden(403)에서 막힌다 — 고정 snapshot 만 시딩해 연결하면 된다(NOT NULL).
        val failedItem = itemJpaRepository.save(Item())
        val snapshot = saveSnapshot(failedItem.getId(), status = ItemStatus.FAILED)
        tournamentItemJpaRepository.save(TournamentItem(tournamentId = tournamentId, userId = otherUserId, snapshotId = snapshot.getId()))
        val tournamentItemId = tournamentItemJpaRepository.findAllByTournamentIdAndNotDeleted(tournamentId).first().getId()

        mockMvc
            .perform(
                multipart(HttpMethod.PATCH, "/api/v1/tournaments/$tournamentId/items/$tournamentItemId")
                    .param("name", "수정 시도")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `PATCH tournaments-id-items-itemId 는 이름이 있는 FAILED 아이템에 가격만 보내면 이름을 유지하며 200 을 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)
        // 이름과 imageUrl 이 이미 있는 FAILED snapshot — 가격만 보정해도 이름과 이미지가 유지되는지 검증한다(apply 가 기존 값 보존).
        val failedItem = itemJpaRepository.save(Item())
        val snapshot = saveSnapshot(failedItem.getId(), status = ItemStatus.FAILED, name = "기존 이름", imageUrl = "https://img.example.com/a.png")
        tournamentItemJpaRepository.save(
            TournamentItem(tournamentId = tournamentId, userId = userId, snapshotId = snapshot.getId()),
        )
        val tournamentItemId = tournamentItemJpaRepository.findAllByTournamentIdAndNotDeleted(tournamentId).first().getId()

        mockMvc
            .perform(
                multipart(HttpMethod.PATCH, "/api/v1/tournaments/$tournamentId/items/$tournamentItemId")
                    .param("price", "50000")
                    .param("currency", "KRW")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)

        val updated = itemSnapshotJpaRepository.findById(snapshot.getId()).get()
        assertEquals("기존 이름", updated.name)
        assertEquals(50000, updated.currentPrice)
        assertEquals(ItemStatus.READY, updated.status)
    }

    @Test
    fun `PATCH tournaments-id-items-itemId 에서 이름 없이 수정하면 400 을 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)
        val failedItem = itemJpaRepository.save(Item())
        val snapshot = saveSnapshot(failedItem.getId(), status = ItemStatus.FAILED)
        tournamentItemJpaRepository.save(
            TournamentItem(tournamentId = tournamentId, userId = userId, snapshotId = snapshot.getId()),
        )
        val tournamentItemId = tournamentItemJpaRepository.findAllByTournamentIdAndNotDeleted(tournamentId).first().getId()

        mockMvc
            .perform(
                multipart(HttpMethod.PATCH, "/api/v1/tournaments/$tournamentId/items/$tournamentItemId")
                    .param("price", "50000")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `POST tournaments-id-items-link 는 참여자이면 PENDING 아이템을 생성하고 tournamentItemId 를 반환한다`() {
        stubItemParsingWorker.enabled = false
        try {
            val mockMvc = buildMockMvc()
            val tournamentId = createTournament(mockMvc)

            val result =
                mockMvc
                    .perform(
                        post("/api/v1/tournaments/$tournamentId/items/link")
                            .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"url":"https://example.com/product"}"""),
                    ).andExpect(status().isOk)
                    .andExpect(jsonPath("$.data.tournamentItemId").isNumber)
                    .andReturn()

            val tournamentItemId = objectMapper.readTree(result.response.contentAsString)["data"]["tournamentItemId"].asLong()
            val tournamentItem = tournamentItemJpaRepository.findAllByTournamentIdAndNotDeleted(tournamentId).also {
                assertEquals(1, it.size)
            }.first()
            assertEquals(tournamentItemId, tournamentItem.getId())
            // 상태는 활성 snapshot 이 보유한다(4a) — 링크 등록 직후라 PENDING(outbox 적재)으로 시작한다.
            // @Transactional 테스트라 등록이 커밋되지 않아 디스패처(별도 트랜잭션)가 이 PENDING 을 집지 못한다 → PENDING 고정.
            // item 정체성은 snapshot 단일 출처이므로 tournament_item 의 고정 snapshot 으로 itemId 에 도달해 최신 snapshot 을 조회한다.
            val fixedSnapshot = itemSnapshotJpaRepository.findById(tournamentItem.snapshotId).get()
            val snapshot = itemSnapshotJpaRepository.findFirstByItemIdAndDeletedAtIsNullOrderByIdDesc(fixedSnapshot.itemId)
            assertEquals(ItemStatus.PENDING, snapshot?.status)
        } finally {
            stubItemParsingWorker.enabled = true
        }
    }

    @Test
    fun `POST tournaments-id-items-link 에서 url 이 빈 값이면 400 을 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)

        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/items/link")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"url":""}"""),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `POST tournaments-id-items-link 에서 미지원 플랫폼(KREAM) URL 이면 400 을 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)

        // 미지원 플랫폼(봇 차단으로 fetch 불가)은 등록 입력 시점에 동기 400 으로 막는다 — 위시 등록과 같은 메커니즘.
        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/items/link")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"url":"https://kream.co.kr/products/950123"}"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value("아직 지원하지 않는 쇼핑몰이에요. 상품 이미지를 직접 등록해 주세요."))

        // 등록 입력 경계에서 차단되므로 tournament item 이 생성되면 안 된다(검증 위치가 뒤로 밀려 일부라도 영속화되는 회귀 방지).
        assertTrue(
            tournamentItemJpaRepository.findAllByTournamentIdAndNotDeleted(tournamentId).isEmpty(),
            "미지원 플랫폼은 등록 입력 경계에서 차단되어 tournament item 이 생성되면 안 됩니다.",
        )
    }

    @Test
    fun `POST tournaments-id-items-link 에서 토너먼트 참여자가 아니면 403 을 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)

        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/items/link")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"url":"https://example.com/product"}"""),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `POST tournaments-id-items-link 에서 토너먼트가 없으면 404 를 반환한다`() {
        val mockMvc = buildMockMvc()

        mockMvc
            .perform(
                post("/api/v1/tournaments/999999/items/link")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"url":"https://example.com/product"}"""),
            ).andExpect(status().isNotFound)
    }

    @Test
    fun `POST tournaments-id-items-link 에서 플레이링크 복제 토너먼트이면 403 을 반환한다`() {
        val mockMvc = buildMockMvc()
        saveUser(otherUserId, "https://cdn.example.com/other.jpg", "다른유저")
        val (sourceTournamentId, _, _) = completeTournamentWith2Items(mockMvc)
        mockMvc.perform(
            post("/api/v1/tournaments/$sourceTournamentId/play-link")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
        val cloneResult = mockMvc.perform(
            post("/api/v1/tournaments/$sourceTournamentId/from-play-link")
                .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId)),
        ).andReturn()
        val cloneId = objectMapper.readTree(cloneResult.response.contentAsString)["data"].asLong()

        mockMvc
            .perform(
                post("/api/v1/tournaments/$cloneId/items/link")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"url":"https://example.com/product"}"""),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `POST tournaments-id-items-images 는 참여자이면 PENDING 아이템을 생성하고 tournamentItemIds 를 반환한다`() {
        stubImageParsingWorker.enabled = false
        try {
            val mockMvc = buildMockMvc()
            val tournamentId = createTournament(mockMvc)
            val image1 = MockMultipartFile("images", "img1.jpg", "image/jpeg", ByteArray(100) { 1 })
            val image2 = MockMultipartFile("images", "img2.jpg", "image/jpeg", ByteArray(100) { 2 })

            mockMvc
                .perform(
                    multipart("/api/v1/tournaments/$tournamentId/items/images")
                        .file(image1)
                        .file(image2)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.data.tournamentItemIds").isArray)
                .andExpect(jsonPath("$.data.tournamentItemIds.length()").value(2))

            assertEquals(2, tournamentItemJpaRepository.findAllByTournamentIdAndNotDeleted(tournamentId).size)
        } finally {
            stubImageParsingWorker.enabled = true
        }
    }

    @Test
    fun `게스트 합류 시 TournamentJoined 이벤트가 발행된다`() {
        val mockMvc = buildMockMvc()
        val (tournamentId, inviteCode) = createTournamentWithInviteCode(mockMvc)

        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/join/guest")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"inviteCode":"$inviteCode","nickname":"새친구"}"""),
            ).andExpect(status().isCreated)

        val joined = applicationEvents.stream(TournamentJoined::class.java).toList()
        assertEquals(1, joined.size)
        assertEquals(tournamentId, joined.first().tournamentId)
    }

    @Test
    fun `링크 아이템 추가 시 TournamentItemAdded 이벤트가 발행된다`() {
        stubItemParsingWorker.enabled = false
        try {
            val mockMvc = buildMockMvc()
            val tournamentId = createTournament(mockMvc)

            mockMvc
                .perform(
                    post("/api/v1/tournaments/$tournamentId/items/link")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"url":"https://example.com/product"}"""),
                ).andExpect(status().isOk)

            val added = applicationEvents.stream(TournamentItemAdded::class.java).toList()
            assertEquals(1, added.size)
            assertEquals(tournamentId, added.first().tournamentId)
            assertEquals(userId, added.first().actorId)
        } finally {
            stubItemParsingWorker.enabled = true
        }
    }

    @Test
    fun `이미지 아이템 추가는 여러 장이어도 TournamentItemAdded 를 한 번만 발행한다`() {
        stubImageParsingWorker.enabled = false
        try {
            val mockMvc = buildMockMvc()
            val tournamentId = createTournament(mockMvc)
            val image1 = MockMultipartFile("images", "img1.jpg", "image/jpeg", ByteArray(100) { 1 })
            val image2 = MockMultipartFile("images", "img2.jpg", "image/jpeg", ByteArray(100) { 2 })

            mockMvc
                .perform(
                    multipart("/api/v1/tournaments/$tournamentId/items/images")
                        .file(image1)
                        .file(image2)
                        .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
                ).andExpect(status().isOk)

            val added = applicationEvents.stream(TournamentItemAdded::class.java).toList()
            assertEquals(1, added.size)
            assertEquals(tournamentId, added.first().tournamentId)
            assertEquals(userId, added.first().actorId)
        } finally {
            stubImageParsingWorker.enabled = true
        }
    }

    @Test
    fun `POST tournaments-id-items-images 에서 토너먼트 참여자가 아니면 403 을 반환하고 raw 를 올리지 않는다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)
        val image = MockMultipartFile("images", "test.jpg", "image/jpeg", ByteArray(100) { 1 })
        val rawBefore = stubImageStorage.uploadedKeys.count { it.startsWith("items/raw/") }

        mockMvc
            .perform(
                multipart("/api/v1/tournaments/$tournamentId/items/images")
                    .file(image)
                    .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId)),
            ).andExpect(status().isForbidden)

        // 권한 검증(verifyCanAddItems)이 업로드 전에 거부하므로 raw 가 S3 에 올라가지 않아야 한다(orphan 방지).
        assertEquals(rawBefore, stubImageStorage.uploadedKeys.count { it.startsWith("items/raw/") })
    }

    @Test
    fun `POST tournaments-id-items-images 에서 플레이링크 복제 토너먼트이면 403 을 반환한다`() {
        val mockMvc = buildMockMvc()
        saveUser(otherUserId, "https://cdn.example.com/other.jpg", "다른유저")
        val (sourceTournamentId, _, _) = completeTournamentWith2Items(mockMvc)
        mockMvc.perform(
            post("/api/v1/tournaments/$sourceTournamentId/play-link")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
        val cloneResult = mockMvc.perform(
            post("/api/v1/tournaments/$sourceTournamentId/from-play-link")
                .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId)),
        ).andReturn()
        val cloneId = objectMapper.readTree(cloneResult.response.contentAsString)["data"].asLong()
        val image = MockMultipartFile("images", "test.jpg", "image/jpeg", ByteArray(100) { 1 })

        mockMvc
            .perform(
                multipart("/api/v1/tournaments/$cloneId/items/images")
                    .file(image)
                    .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId)),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `POST tournaments-id-items-images 에서 이미지 6개 이상이면 400 을 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)
        val images = (1..6).map { i -> MockMultipartFile("images", "img$i.jpg", "image/jpeg", ByteArray(100) { 1 }) }

        val request = images.fold(multipart("/api/v1/tournaments/$tournamentId/items/images")) { req, file ->
            req.file(file)
        }.header(HttpHeaders.AUTHORIZATION, authHeader(userId))

        mockMvc
            .perform(request)
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `POST tournaments-id-items-images 에서 이미지 파트를 보내지 않으면 400 을 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)

        // .file(...) 없이 images 파트를 아예 생략 — required=false + orEmpty 로 서비스 검증(개수 0)에 닿아 400.
        mockMvc
            .perform(
                multipart("/api/v1/tournaments/$tournamentId/items/images")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `POST tournaments-id-items 에서 위시리스트에 없는 아이템이면 403 을 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)
        // saveItem 은 위시 없이 아이템만 저장 — wish 소유 확인에서 실패해야 한다
        val itemId = saveItem()

        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/items/wish")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"itemIds":[$itemId]}"""),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `POST tournaments-id-items-link 에서 32개 초과 시 400 을 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)
        val full32 = (1..32).map { saveWishItem() }.toLongArray()
        addItemsToTournament(mockMvc, tournamentId, userId, *full32)

        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/items/link")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"url":"https://example.com/product"}"""),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `POST tournaments-id-items-images 에서 32개 초과 시 400 을 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)
        val full32 = (1..32).map { saveWishItem() }.toLongArray()
        addItemsToTournament(mockMvc, tournamentId, userId, *full32)
        val image = MockMultipartFile("images", "img.jpg", "image/jpeg", ByteArray(100) { 1 })

        mockMvc
            .perform(
                multipart("/api/v1/tournaments/$tournamentId/items/images")
                    .file(image)
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isBadRequest)
    }

    private fun buildMockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    private fun createTournament(
        mockMvc: MockMvc,
        name: String = "테스트 토너먼트",
    ): Long {
        val result =
            mockMvc
                .perform(
                    post("/api/v1/tournaments")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"$name"}"""),
                ).andReturn()
        return objectMapper.readTree(result.response.contentAsString)["data"]["tournamentId"].asLong()
    }

    private fun createTournamentWithInviteCode(
        mockMvc: MockMvc,
        name: String = "테스트 토너먼트",
    ): Pair<Long, String> {
        val result =
            mockMvc
                .perform(
                    post("/api/v1/tournaments")
                        .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"$name"}"""),
                ).andReturn()
        val data = objectMapper.readTree(result.response.contentAsString)["data"]
        return data["tournamentId"].asLong() to data["inviteCode"].asText()
    }

    private fun saveUser(
        id: UUID,
        profileImage: String,
        nickname: String = "테스트유저",
    ): User =
        userJpaRepository.save(
            User(id = id, nickname = nickname, profileImage = profileImage, identityType = IdentityType.MEMBER),
        )

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

    private data class TournamentStart(
        val tournamentId: Long,
        val item1Id: Long,
        val item2Id: Long,
    )

    // 위시 없이 item 정체성만 저장 — wish 소유 확인 실패(403) 시나리오용. 표시값·상태는 snapshot 소관이라 여기선 빈 item 만.
    private fun saveItem(): Long = itemJpaRepository.save(Item()).getId()

    // 직접 저장하는 tournament_item 에 3단계 쓰기 계약(고정 snapshot)을 맞춰준다 — 원하는 표시값·상태를 가진
    // snapshot 을 만들고 그 id 를 박아 저장한다. 조회 경로(getTournamentById·getTournamentItem)가 snapshot 을 읽기 때문이다.
    // item 은 정체성(link)만 들고 추출값·상태는 snapshot 이 보유한다(4a).
    // (서비스 경유 시딩 saveWishItem+addItemsToTournament 은 엔드포인트가 이미 snapshotId 를 채운다.)
    private fun saveTournamentItemFor(
        tournamentId: Long,
        item: Item,
        status: ItemStatus = ItemStatus.READY,
        name: String? = null,
        price: Int? = null,
        currency: String? = null,
        imageUrl: String? = null,
        owner: UUID = userId,
    ): TournamentItem {
        val snapshot =
            itemSnapshotJpaRepository.save(
                ItemSnapshot(
                    itemId = item.getId(),
                    name = name,
                    currentPrice = price,
                    currency = currency,
                    imageUrl = imageUrl,
                    status = status,
                    extractedAt = if (status == ItemStatus.READY) LocalDateTime.now() else null,
                ),
            )
        return tournamentItemJpaRepository.save(
            TournamentItem(
                tournamentId = tournamentId,
                userId = owner,
                snapshotId = snapshot.getId(),
            ),
        )
    }

    // 원하는 상태·표시값을 가진 snapshot 을 한 행 저장한다 — tournament_item 의 snapshotId 가 가리킬 고정 버전용.
    // 추출값·상태가 snapshot 으로 모였으므로(4a), FAILED/PROCESSING 시드는 이 snapshot 을 만들어 연결한다.
    private fun saveSnapshot(
        itemId: Long,
        status: ItemStatus,
        name: String? = null,
        price: Int? = null,
        currency: String? = null,
        imageUrl: String? = null,
    ): ItemSnapshot =
        itemSnapshotJpaRepository.save(
            ItemSnapshot(
                itemId = itemId,
                name = name,
                currentPrice = price,
                currency = currency,
                imageUrl = imageUrl,
                status = status,
                extractedAt = if (status == ItemStatus.READY) LocalDateTime.now() else null,
            ),
        )

    // 위시리스트에도 등록된 READY 아이템 생성 — /items/wish 엔드포인트용. 이미지 등록류(link 없이 sourceImageKey)라 sourceUrl 이 없다.
    // 이미지 경로도 link 처럼 PENDING 으로 outbox 적재되므로, persistPendingImages 로 만든 뒤 claim(PROCESSING)→markReady 로
    // 전이시켜 추출값을 채운다. 표시값·상태는 활성 snapshot 이 보유한다.
    private fun saveWishItem(owner: UUID = userId, name: String = "테스트 아이템", price: Int = 10_000): Long {
        val result = wishPersistenceService.persistPendingImages(owner, listOf("items/raw/${UUID.randomUUID()}.png")).first()
        itemSnapshotJpaRepository.findById(result.snapshot.getId()).get().markProcessing()
        itemParsingService.markReady(
            result.snapshot.getId(),
            ProductSnapshot(name = name, currentPrice = price, currency = "KRW", imageUrl = "https://img.example.com/a.png"),
        )
        return result.item.getId()
    }

    @Test
    fun `GET tournaments-id-items-tournamentItemId 는 READY 아이템의 이름·가격·이미지·status 를 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)
        val itemId = saveWishItem(name = "나이키 에어맥스", price = 129_000)
        addItemsToTournament(mockMvc, tournamentId, userId, itemId)
        val tournamentItemId = tournamentItemJpaRepository.findAllByTournamentIdAndNotDeleted(tournamentId).first().getId()

        mockMvc
            .perform(
                get("/api/v1/tournaments/$tournamentId/items/$tournamentItemId")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.tournamentItemId").value(tournamentItemId))
            .andExpect(jsonPath("$.data.itemId").value(itemId))
            .andExpect(jsonPath("$.data.name").value("나이키 에어맥스"))
            .andExpect(jsonPath("$.data.price").value(129_000))
            .andExpect(jsonPath("$.data.currency").value("KRW"))
            .andExpect(jsonPath("$.data.status").value("READY"))
            // 이미지·위시 등록 아이템은 sourceUrl 이 없어 응답에 포함되지 않는다
            .andExpect(jsonPath("$.data.sourceUrl").doesNotExist())
    }

    @Test
    fun `GET tournaments-id-items-tournamentItemId 는 링크 등록 아이템의 sourceUrl 을 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)
        val sourceUrl = "https://www.nike.com/kr/t/air-max/example"
        // link(원본 URL)는 정체성이라 item 에, 표시값은 READY snapshot 에 둔다(4a).
        val linkItem = itemJpaRepository.save(Item(link = ProductLink.parse(sourceUrl)))
        saveTournamentItemFor(tournamentId, linkItem, name = "나이키", price = 100_000, currency = "KRW")
        val tournamentItemId = tournamentItemJpaRepository.findAllByTournamentIdAndNotDeleted(tournamentId).first().getId()

        mockMvc
            .perform(
                get("/api/v1/tournaments/$tournamentId/items/$tournamentItemId")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.sourceUrl").value(sourceUrl))
            .andExpect(jsonPath("$.data.status").value("READY"))
    }

    @Test
    fun `GET tournaments-id-items-tournamentItemId 는 PROCESSING 아이템이면 name·price·imageUrl 이 응답에 없고 status 가 PROCESSING 이다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)
        val processingItem = itemJpaRepository.save(Item())
        saveTournamentItemFor(tournamentId, processingItem, status = ItemStatus.PROCESSING)
        val tournamentItemId = tournamentItemJpaRepository.findAllByTournamentIdAndNotDeleted(tournamentId).first().getId()

        mockMvc
            .perform(
                get("/api/v1/tournaments/$tournamentId/items/$tournamentItemId")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("PROCESSING"))
            .andExpect(jsonPath("$.data.name").doesNotExist())
            .andExpect(jsonPath("$.data.price").doesNotExist())
            .andExpect(jsonPath("$.data.imageUrl").doesNotExist())
    }

    @Test
    fun `GET tournaments-id-items-tournamentItemId 에서 토너먼트 참여자가 아니면 403 을 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)
        addItemsToTournament(mockMvc, tournamentId, userId, saveWishItem())
        val tournamentItemId = tournamentItemJpaRepository.findAllByTournamentIdAndNotDeleted(tournamentId).first().getId()

        mockMvc
            .perform(
                get("/api/v1/tournaments/$tournamentId/items/$tournamentItemId")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId)),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `GET tournaments-id-items-tournamentItemId 에서 존재하지 않는 tournamentId 이면 404 를 반환한다`() {
        val mockMvc = buildMockMvc()

        mockMvc
            .perform(
                get("/api/v1/tournaments/999999/items/1")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isNotFound)
    }

    @Test
    fun `GET tournaments-id-items-tournamentItemId 에서 존재하지 않는 tournamentItemId 이면 404 를 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)

        mockMvc
            .perform(
                get("/api/v1/tournaments/$tournamentId/items/999999")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isNotFound)
    }

    @Test
    fun `GET tournaments-id-items-tournamentItemId 에서 다른 토너먼트 소속 아이템이면 404 를 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId1 = createTournament(mockMvc, "토너먼트1")
        val tournamentId2 = createTournament(mockMvc, "토너먼트2")
        addItemsToTournament(mockMvc, tournamentId2, userId, saveWishItem())
        val itemOfTournament2 = tournamentItemJpaRepository.findAllByTournamentIdAndNotDeleted(tournamentId2).first().getId()

        mockMvc
            .perform(
                get("/api/v1/tournaments/$tournamentId1/items/$itemOfTournament2")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isNotFound)
    }

    @Test
    fun `DELETE tournaments-id 는 소유자가 PENDING 토너먼트를 삭제하면 200 을 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)

        mockMvc
            .perform(
                delete("/api/v1/tournaments/$tournamentId")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
    }

    @Test
    fun `DELETE tournaments-id 에서 IN_PROGRESS 토너먼트 삭제 시도 시 409 를 반환한다`() {
        val mockMvc = buildMockMvc()
        val (tournamentId) = startTournamentWith2Items(mockMvc)

        mockMvc
            .perform(
                delete("/api/v1/tournaments/$tournamentId")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isConflict)
    }

    @Test
    fun `DELETE tournaments-id 에서 소유자가 아닌 사용자가 요청하면 403 을 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)

        mockMvc
            .perform(
                delete("/api/v1/tournaments/$tournamentId")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId)),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `DELETE tournaments-id 에서 존재하지 않는 토너먼트이면 404 를 반환한다`() {
        val mockMvc = buildMockMvc()

        mockMvc
            .perform(
                delete("/api/v1/tournaments/999999")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isNotFound)
    }

    @Test
    fun `DELETE tournaments-id PENDING 토너먼트 삭제 후 소유자가 조회하면 404 를 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)
        mockMvc.perform(
            delete("/api/v1/tournaments/$tournamentId")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
        )

        // Design B: PENDING 삭제는 토너먼트 자체를 소프트 딜리트하므로 조회 시 404
        mockMvc
            .perform(
                get("/api/v1/tournaments/$tournamentId")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isNotFound)
    }

    // ── 초대 기한 수정 ──────────────────────────────────────────────────

    @Test
    fun `PATCH tournaments-id-invite 는 주최자가 200 과 함께 새 inviteExpiresAt 을 반환하고 DB 에도 반영된다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)
        val newExpiresAt = LocalDateTime.now().plusMinutes(60).withNano(0)

        mockMvc
            .perform(
                patch("/api/v1/tournaments/$tournamentId/invite")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"newExpiresAt":"$newExpiresAt"}"""),
            ).andExpect(status().isOk)

        val saved = tournamentJpaRepository.findByIdAndDeletedAtIsNull(tournamentId)!!
        assertEquals(newExpiresAt, saved.inviteExpiresAt)
    }

    @Test
    fun `PATCH tournaments-id-invite 에서 newExpiresAt 이 과거 시각이면 400 을 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)
        val pastTime = LocalDateTime.now().minusMinutes(1).withNano(0)

        mockMvc
            .perform(
                patch("/api/v1/tournaments/$tournamentId/invite")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"newExpiresAt":"$pastTime"}"""),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `PATCH tournaments-id-invite 에서 참여자이지만 주최자가 아니면 403 을 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)
        tournamentUserJpaRepository.save(TournamentUser(tournamentId = tournamentId, userId = otherUserId))

        mockMvc
            .perform(
                patch("/api/v1/tournaments/$tournamentId/invite")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"newExpiresAt":"${LocalDateTime.now().plusMinutes(60).withNano(0)}"}"""),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `PATCH tournaments-id-invite 에서 존재하지 않는 토너먼트이면 404 를 반환한다`() {
        val mockMvc = buildMockMvc()

        mockMvc
            .perform(
                patch("/api/v1/tournaments/999999/invite")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"newExpiresAt":"${LocalDateTime.now().plusMinutes(60).withNano(0)}"}"""),
            ).andExpect(status().isNotFound)
    }

    @Test
    fun `PATCH tournaments-id-invite 에서 PENDING 이 아닌 토너먼트이면 409 를 반환한다`() {
        val mockMvc = buildMockMvc()
        val (tournamentId) = startTournamentWith2Items(mockMvc)

        mockMvc
            .perform(
                patch("/api/v1/tournaments/$tournamentId/invite")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"newExpiresAt":"${LocalDateTime.now().plusMinutes(60).withNano(0)}"}"""),
            ).andExpect(status().isConflict)
    }

    // ── 초대 미리보기 ──────────────────────────────────────────────────

    @Test
    fun `GET invite-preview 는 tournamentId 만으로 토너먼트 정보를 반환한다`() {
        val mockMvc = buildMockMvc()
        val (tournamentId, _) = createTournamentWithInviteCode(mockMvc)

        mockMvc
            .perform(get("/api/v1/tournaments/$tournamentId/invite-preview"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.tournamentId").value(tournamentId))
            .andExpect(jsonPath("$.data.tournamentName").isString)
            .andExpect(jsonPath("$.data.itemCount").value(0))
            .andExpect(jsonPath("$.data.participantCount").value(1))
            // 토큰 없이 호출 → 참여 여부를 알 수 없으므로 joined=false
            .andExpect(jsonPath("$.data.joined").value(false))
    }

    @Test
    fun `GET invite-preview 는 참여자 토큰이면 joined=true 를 반환한다`() {
        val mockMvc = buildMockMvc()
        val (tournamentId, _) = createTournamentWithInviteCode(mockMvc)

        mockMvc
            .perform(
                get("/api/v1/tournaments/$tournamentId/invite-preview")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.joined").value(true))
    }

    @Test
    fun `GET invite-preview 는 미참여 유저 토큰이면 joined=false 를 반환한다`() {
        val mockMvc = buildMockMvc()
        val (tournamentId, _) = createTournamentWithInviteCode(mockMvc)
        saveUser(otherUserId, "https://cdn.example.com/other.jpg", "다른유저")

        mockMvc
            .perform(
                get("/api/v1/tournaments/$tournamentId/invite-preview")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.joined").value(false))
    }

    @Test
    fun `GET invite-preview 는 JWT 없이도 호출 가능하다`() {
        val mockMvc = buildMockMvc()
        val (tournamentId, _) = createTournamentWithInviteCode(mockMvc)

        mockMvc
            .perform(get("/api/v1/tournaments/$tournamentId/invite-preview"))
            .andExpect(status().isOk)
    }

    @Test
    fun `GET by-invite-code 는 유효한 코드로 tournamentId 를 포함한 토너먼트 정보를 반환한다`() {
        val mockMvc = buildMockMvc()
        val (tournamentId, inviteCode) = createTournamentWithInviteCode(mockMvc)

        mockMvc
            .perform(get("/api/v1/tournaments/by-invite-code").param("code", inviteCode))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.tournamentId").value(tournamentId))
            .andExpect(jsonPath("$.data.tournamentName").isString)
            // 토큰 없이 호출 → joined=false
            .andExpect(jsonPath("$.data.joined").value(false))
    }

    @Test
    fun `GET by-invite-code 는 참여자 토큰이면 joined=true 를 반환한다`() {
        val mockMvc = buildMockMvc()
        val (_, inviteCode) = createTournamentWithInviteCode(mockMvc)

        mockMvc
            .perform(
                get("/api/v1/tournaments/by-invite-code")
                    .param("code", inviteCode)
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.joined").value(true))
    }

    @Test
    fun `GET by-invite-code 는 JWT 없이도 호출 가능하다`() {
        val mockMvc = buildMockMvc()
        val (_, inviteCode) = createTournamentWithInviteCode(mockMvc)

        mockMvc
            .perform(get("/api/v1/tournaments/by-invite-code").param("code", inviteCode))
            .andExpect(status().isOk)
    }

    @Test
    fun `GET by-invite-code 는 존재하지 않는 코드이면 400 을 반환한다`() {
        val mockMvc = buildMockMvc()

        mockMvc
            .perform(get("/api/v1/tournaments/by-invite-code").param("code", "ZZZ999"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `GET by-invite-code 는 초대 링크가 만료된 토너먼트이면 409 를 반환한다`() {
        val mockMvc = buildMockMvc()
        val expiredCode = "EXP001"
        tournamentJpaRepository.save(
            Tournament(
                ownerTournamentUserId = 0L,
                name = "만료 토너먼트",
                inviteCode = expiredCode,
                inviteExpiresAt = java.time.LocalDateTime.now().minusMinutes(1),
            ),
        )

        mockMvc
            .perform(get("/api/v1/tournaments/by-invite-code").param("code", expiredCode))
            .andExpect(status().isConflict)
    }

    @Test
    fun `GET by-invite-code 는 soft-delete 된 토너먼트의 코드이면 400 을 반환한다`() {
        val mockMvc = buildMockMvc()
        val (tournamentId, inviteCode) = createTournamentWithInviteCode(mockMvc)
        mockMvc.perform(
            delete("/api/v1/tournaments/$tournamentId")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
        ).andExpect(status().isOk)

        // 조회는 active_invite_code(= IF(deleted_at IS NULL, invite_code, NULL)) 로 태운다.
        // soft-delete 되면 active_invite_code 가 NULL 이 되어 코드로 조회되지 않아야 한다.
        mockMvc
            .perform(get("/api/v1/tournaments/by-invite-code").param("code", inviteCode))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `GET by-invite-code 는 PENDING 이 아닌 토너먼트이면 409 를 반환한다`() {
        val mockMvc = buildMockMvc()
        val (tournamentId, inviteCode) = createTournamentWithInviteCode(mockMvc)
        addItemsToTournament(mockMvc, tournamentId, userId, saveWishItem(name = "아이템1"), saveWishItem(name = "아이템2"))
        mockMvc.perform(
            post("/api/v1/tournaments/$tournamentId/start")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
        )

        mockMvc
            .perform(get("/api/v1/tournaments/by-invite-code").param("code", inviteCode))
            .andExpect(status().isConflict)
    }

    // ── 플레이 링크 ──────────────────────────────────────────────────

    @Test
    fun `POST play-link 는 완료된 토너먼트에 소유자가 플레이 링크를 생성한다`() {
        val mockMvc = buildMockMvc()
        val (tournamentId, _, _) = completeTournamentWith2Items(mockMvc)

        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/play-link")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data").isString)
    }

    @Test
    fun `POST play-link 는 이미 생성된 경우 재생성 시 409 를 반환한다`() {
        val mockMvc = buildMockMvc()
        val (tournamentId, _, _) = completeTournamentWith2Items(mockMvc)
        mockMvc.perform(
            post("/api/v1/tournaments/$tournamentId/play-link")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )

        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/play-link")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"),
            ).andExpect(status().isConflict)
    }

    @Test
    fun `POST play-link 는 PENDING 토너먼트에는 409 를 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)

        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/play-link")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"),
            ).andExpect(status().isConflict)
    }

    @Test
    fun `GET play-link-info 는 JWT 없이도 호출 가능하다`() {
        val mockMvc = buildMockMvc()
        val (tournamentId, _, _) = completeTournamentWith2Items(mockMvc)
        mockMvc.perform(
            post("/api/v1/tournaments/$tournamentId/play-link")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )

        mockMvc
            .perform(get("/api/v1/tournaments/$tournamentId/play-link-info"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.sourceTournamentId").value(tournamentId))
            .andExpect(jsonPath("$.data.tournamentName").isString)
    }

    @Test
    fun `GET play-link-info 는 플레이 링크가 없으면 404 를 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)

        mockMvc
            .perform(get("/api/v1/tournaments/$tournamentId/play-link-info"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `POST from-play-link 는 원본 아이템 구성으로 새 토너먼트를 생성한다`() {
        val mockMvc = buildMockMvc()
        saveUser(otherUserId, "https://cdn.example.com/other.jpg", "다른유저")
        val (tournamentId, _, _) = completeTournamentWith2Items(mockMvc)
        mockMvc.perform(
            post("/api/v1/tournaments/$tournamentId/play-link")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )

        val result = mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/from-play-link")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data").isNumber)
            .andReturn()

        val newTournamentId = objectMapper.readTree(result.response.contentAsString)["data"].asLong()
        val cloned = tournamentJpaRepository.findByIdAndDeletedAtIsNull(newTournamentId)!!
        assertEquals(tournamentId, cloned.sourceTournamentId)

        // Design B: CLONE 은 아이템을 DB 에 복사하지 않는다. sourceTournamentId 를 통해 원본 아이템을 참조한다.
        assertTrue(tournamentItemJpaRepository.findAllByTournamentIdAndNotDeleted(newTournamentId).isEmpty())
        assertTrue(tournamentItemJpaRepository.findAllByTournamentIdAndNotDeleted(tournamentId).isNotEmpty())
    }

    @Test
    fun `POST from-play-link 는 같은 유저가 동일 플레이 링크로 재호출 시 200 으로 기존 클론 id 를 반환한다`() {
        val mockMvc = buildMockMvc()
        saveUser(otherUserId, "https://cdn.example.com/other.jpg", "다른유저")
        val (tournamentId, _, _) = completeTournamentWith2Items(mockMvc)
        mockMvc.perform(
            post("/api/v1/tournaments/$tournamentId/play-link")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
        val firstResult = mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/from-play-link")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId)),
            ).andExpect(status().isOk)
            .andReturn()
        val firstCloneId = objectMapper.readTree(firstResult.response.contentAsString)["data"].asLong()

        // 재호출은 새로 만들지 않고 기존 본인 클론 id 를 그대로 반환한다 (idempotent get-or-create).
        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/from-play-link")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data").value(firstCloneId))

        // 클론은 정확히 1개만 생성된다.
        assertEquals(1, tournamentJpaRepository.findAll().count { it.sourceTournamentId == tournamentId })
    }

    @Test
    fun `POST from-play-link 는 원본 플레이 링크가 만료돼도 이미 만든 본인 클론 id 를 반환한다`() {
        val mockMvc = buildMockMvc()
        saveUser(otherUserId, "https://cdn.example.com/other.jpg", "다른유저")
        val (tournamentId, _, _) = completeTournamentWith2Items(mockMvc)
        mockMvc.perform(
            post("/api/v1/tournaments/$tournamentId/play-link")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
        val firstResult = mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/from-play-link")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId)),
            ).andExpect(status().isOk)
            .andReturn()
        val cloneId = objectMapper.readTree(firstResult.response.contentAsString)["data"].asLong()

        // 원본 플레이 링크를 만료시킨다.
        val source = tournamentJpaRepository.findByIdAndDeletedAtIsNull(tournamentId)!!
        source.expirePlayLink()
        tournamentJpaRepository.save(source)

        // 이미 만든 본인 클론은 원본 링크 만료와 무관하게 그대로 반환된다 (이어서 진행하기).
        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/from-play-link")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data").value(cloneId))
    }

    @Test
    fun `POST from-play-link 는 클론이 없는 유저가 만료된 플레이 링크로 호출하면 409 를 반환한다`() {
        val mockMvc = buildMockMvc()
        saveUser(otherUserId, "https://cdn.example.com/other.jpg", "다른유저")
        val (tournamentId, _, _) = completeTournamentWith2Items(mockMvc)
        mockMvc.perform(
            post("/api/v1/tournaments/$tournamentId/play-link")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
        val source = tournamentJpaRepository.findByIdAndDeletedAtIsNull(tournamentId)!!
        source.expirePlayLink()
        tournamentJpaRepository.save(source)

        // 아직 본인 클론이 없는 유저는 신규 생성 경로의 만료 검증에 걸려 409.
        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/from-play-link")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId)),
            ).andExpect(status().isConflict)
    }

    @Test
    fun `POST from-play-link 는 타인 클론에 참여만 한 유저를 본인 클론으로 오인하지 않고 새로 생성한다`() {
        val mockMvc = buildMockMvc()
        val thirdUserId = UUID.randomUUID()
        saveUser(otherUserId, "https://cdn.example.com/other.jpg", "다른유저")
        saveUser(thirdUserId, "https://cdn.example.com/third.jpg", "제3유저")
        val (tournamentId, _, _) = completeTournamentWith2Items(mockMvc)
        mockMvc.perform(
            post("/api/v1/tournaments/$tournamentId/play-link")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
        // otherUser 가 클론을 생성 (소유자)
        val cloneResult = mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/from-play-link")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId)),
            ).andExpect(status().isOk)
            .andReturn()
        val otherCloneId = objectMapper.readTree(cloneResult.response.contentAsString)["data"].asLong()

        // thirdUser 가 otherUser 의 클론(PENDING)에 초대코드로 참여만 한다 (소유자가 아님)
        val otherClone = tournamentJpaRepository.findByIdAndDeletedAtIsNull(otherCloneId)!!
        mockMvc
            .perform(
                post("/api/v1/tournaments/$otherCloneId/join")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(thirdUserId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"inviteCode":"${otherClone.inviteCode}"}"""),
            ).andExpect(status().isOk)

        // thirdUser 의 from-play-link 는 타인 클론을 돌려주지 않고 본인 새 클론을 만든다.
        val result = mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/from-play-link")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(thirdUserId)),
            ).andExpect(status().isOk)
            .andReturn()
        val thirdCloneId = objectMapper.readTree(result.response.contentAsString)["data"].asLong()
        assertNotEquals(otherCloneId, thirdCloneId)
    }

    // ── 그룹 결과 ──────────────────────────────────────────────────

    @Test
    fun `GET group-result 는 완료된 토너먼트의 그룹 결과를 반환하고 활성 참여자는 isWithdrawn=false 다`() {
        val mockMvc = buildMockMvc()
        saveUser(userId, userProfileImage, "활성유저")
        // Design B: group-result 는 2명 이상 완료 시에만 조회 가능하다.
        val tournamentId = completeSocialTournamentWith2Players(mockMvc)

        mockMvc
            .perform(
                get("/api/v1/tournaments/$tournamentId/group-result")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items").isArray)
            .andExpect(jsonPath("$.data.items[0].chosenBy[0].isWithdrawn").value(false))
    }

    @Test
    fun `GET group-result 의 참여자가 탈퇴 유저면 isWithdrawn=true 로 내려온다`() {
        val mockMvc = buildMockMvc()
        val owner = saveUser(userId, userProfileImage, "곧나갈사람")
        // Design B: group-result 는 2명 이상 완료 시에만 조회 가능하다.
        val tournamentId = completeSocialTournamentWith2Players(mockMvc)
        // 그룹 결과 참여자(= 토너먼트 owner)를 탈퇴 tombstone 으로 전이시킨다.
        owner.withdraw()
        userJpaRepository.save(owner)

        mockMvc
            .perform(
                get("/api/v1/tournaments/$tournamentId/group-result")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items[0].chosenBy[0].isWithdrawn").value(true))
    }

    @Test
    fun `GET group-result 는 PENDING 토너먼트에는 409 를 반환한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)

        mockMvc
            .perform(
                get("/api/v1/tournaments/$tournamentId/group-result")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isConflict)
    }

    @Test
    fun `GET group-result 는 게스트(플레이링크 참여자)도 본인 완료 후 ROOT id 로 조회할 수 있다`() {
        val mockMvc = buildMockMvc()
        saveUser(userId, userProfileImage, "주최자")
        saveUser(otherUserId, "https://cdn.example.com/guest.jpg", "게스트")
        // 주최자가 ROOT 생성·시작·완료
        val (rootId, ti1, ti2) = completeTournamentWith2Items(mockMvc)
        // 주최자가 플레이링크 생성
        mockMvc.perform(
            post("/api/v1/tournaments/$rootId/play-link")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
        // 게스트가 플레이링크로 본인 CLONE 생성 → 시작 → 완료
        val cloneResult = mockMvc
            .perform(
                post("/api/v1/tournaments/$rootId/from-play-link")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId)),
            ).andReturn()
        val cloneId = objectMapper.readTree(cloneResult.response.contentAsString)["data"].asLong()
        mockMvc.perform(
            post("/api/v1/tournaments/$cloneId/start")
                .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId)),
        )
        mockMvc.perform(
            post("/api/v1/tournaments/$cloneId/matches")
                .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"currentRound":2,"firstTournamentItemId":$ti1,"secondTournamentItemId":$ti2,"selectedTournamentItemId":$ti1}""",
                ),
        )

        // 게스트(ROOT TU 아님, 본인 CLONE 소유자)가 ROOT id 로 그룹 결과 조회 → 200
        mockMvc
            .perform(
                get("/api/v1/tournaments/$rootId/group-result")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items").isArray)
    }

    @Test
    fun `GET group-result 는 ROOT 참여자도 본인 CLONE 소유자도 아니면 403 을 반환한다`() {
        val mockMvc = buildMockMvc()
        saveUser(userId, userProfileImage, "주최자")
        val outsiderId = UUID.randomUUID()
        saveUser(outsiderId, "https://cdn.example.com/outsider.jpg", "외부인")
        val rootId = completeSocialTournamentWith2Players(mockMvc)

        // outsiderId 는 ROOT 의 TournamentUser 도, ROOT 클론의 소유자도 아니다.
        mockMvc
            .perform(
                get("/api/v1/tournaments/$rootId/group-result")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(outsiderId)),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `POST tournaments-id-join 시 TournamentJoined 이벤트가 발행된다`() {
        val mockMvc = buildMockMvc()
        saveUser(otherUserId, "https://cdn.example.com/other.jpg", "다른유저")
        val (tournamentId, inviteCode) = createTournamentWithInviteCode(mockMvc)

        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/join")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"inviteCode":"$inviteCode"}"""),
            ).andExpect(status().isOk)

        val events = applicationEvents.stream(TournamentJoined::class.java).toList()
        assertEquals(1, events.size)
        assertEquals(tournamentId, events.first().tournamentId)
        assertEquals(otherUserId, events.first().actorId)
    }

    @Test
    fun `POST tournaments-id-items-wish 에서 여러 아이템을 추가해도 TournamentItemAdded 를 한 번만 발행한다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)

        addItemsToTournament(mockMvc, tournamentId, userId, saveWishItem(name = "아이템1"), saveWishItem(name = "아이템2"))

        val events = applicationEvents.stream(TournamentItemAdded::class.java).toList()
        assertEquals(1, events.size)
        assertEquals(tournamentId, events.first().tournamentId)
        assertEquals(userId, events.first().actorId)
    }

    @Test
    fun `POST tournaments-id-start 시 TournamentStarted 이벤트가 발행된다`() {
        val mockMvc = buildMockMvc()
        val tournamentId = createTournament(mockMvc)
        addItemsToTournament(mockMvc, tournamentId, userId, saveWishItem(), saveWishItem())

        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/start")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
            ).andExpect(status().isOk)

        val events = applicationEvents.stream(TournamentStarted::class.java).toList()
        assertEquals(1, events.size)
        assertEquals(tournamentId, events.first().tournamentId)
        assertEquals(userId, events.first().actorId)
    }

    @Test
    fun `POST tournaments-id-matches 결승 완료 시 ROOT 토너먼트이면 TournamentResultReady 를 발행한다`() {
        val mockMvc = buildMockMvc()
        val (tournamentId, ti1, ti2) = startTournamentWith2Items(mockMvc)

        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/matches")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"currentRound":2,"firstTournamentItemId":$ti1,"secondTournamentItemId":$ti2,"selectedTournamentItemId":$ti1}"""),
            ).andExpect(status().isOk)

        val events = applicationEvents.stream(TournamentResultReady::class.java).toList()
        assertEquals(1, events.size)
        assertEquals(tournamentId, events.first().rootTournamentId)
        assertEquals(userId, events.first().actorId)
    }

    @Test
    fun `POST tournaments-id-matches 결승 완료 시 CLONE 토너먼트이면 TournamentCompleted 를 발행한다`() {
        val mockMvc = buildMockMvc()
        saveUser(otherUserId, "https://cdn.example.com/other.jpg", "다른유저")
        val rootId = createTournament(mockMvc)
        mockMvc.perform(
            post("/api/v1/tournaments/$rootId/join")
                .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"inviteCode":null}"""),
        )
        addItemsToTournament(mockMvc, rootId, userId, saveWishItem(), saveWishItem())
        mockMvc.perform(
            post("/api/v1/tournaments/$rootId/start")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
        )
        val rootItems = tournamentItemJpaRepository.findAllByTournamentIdAndNotDeleted(rootId)
        val ti1 = rootItems[0].getId()
        val ti2 = rootItems[1].getId()
        mockMvc.perform(
            post("/api/v1/tournaments/$rootId/matches")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentRound":2,"firstTournamentItemId":$ti1,"secondTournamentItemId":$ti2,"selectedTournamentItemId":$ti1}"""),
        )
        val startResult = mockMvc.perform(
            post("/api/v1/tournaments/$rootId/start")
                .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId)),
        ).andReturn()
        val cloneId = objectMapper.readTree(startResult.response.contentAsString)["data"]["tournamentId"].asLong()

        mockMvc
            .perform(
                post("/api/v1/tournaments/$cloneId/matches")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"currentRound":2,"firstTournamentItemId":$ti1,"secondTournamentItemId":$ti2,"selectedTournamentItemId":$ti1}"""),
            ).andExpect(status().isOk)

        val events = applicationEvents.stream(TournamentCompleted::class.java).toList()
        assertEquals(1, events.size)
        assertEquals(rootId, events.first().rootTournamentId)
        assertEquals(otherUserId, events.first().actorId)
    }

    @Test
    fun `POST from-play-link 신규 클론 생성 시 TournamentPlayedFromLink 를 발행한다`() {
        val mockMvc = buildMockMvc()
        saveUser(otherUserId, "https://cdn.example.com/other.jpg", "다른유저")
        val (rootId, _, _) = completeTournamentWith2Items(mockMvc)
        mockMvc.perform(
            post("/api/v1/tournaments/$rootId/play-link")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )

        mockMvc
            .perform(
                post("/api/v1/tournaments/$rootId/from-play-link")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"),
            ).andExpect(status().isOk)

        val events = applicationEvents.stream(TournamentPlayedFromLink::class.java).toList()
        assertEquals(1, events.size)
        assertEquals(rootId, events.first().rootTournamentId)
        assertEquals(otherUserId, events.first().actorId)
    }

    @Test
    fun `POST from-play-link 기존 클론 재요청 시 TournamentPlayedFromLink 를 발행하지 않는다`() {
        val mockMvc = buildMockMvc()
        saveUser(otherUserId, "https://cdn.example.com/other.jpg", "다른유저")
        val (rootId, _, _) = completeTournamentWith2Items(mockMvc)
        mockMvc.perform(
            post("/api/v1/tournaments/$rootId/play-link")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
        // 1회차: 신규 클론 생성 (이벤트 발행)
        mockMvc.perform(
            post("/api/v1/tournaments/$rootId/from-play-link")
                .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
        // 2회차: 기존 클론 반환 (이벤트 미발행) — 테스트 메서드 내 총 발행 횟수가 1 이어야 한다
        mockMvc
            .perform(
                post("/api/v1/tournaments/$rootId/from-play-link")
                    .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"),
            ).andExpect(status().isOk)

        val events = applicationEvents.stream(TournamentPlayedFromLink::class.java).toList()
        assertEquals(1, events.size)
    }

    private fun completeTournamentWith2Items(mockMvc: MockMvc): TournamentStart {
        val tournamentId = createTournament(mockMvc)
        val item1Id = saveWishItem(name = "아이템1")
        val item2Id = saveWishItem(name = "아이템2")
        addItemsToTournament(mockMvc, tournamentId, userId, item1Id, item2Id)
        mockMvc.perform(
            post("/api/v1/tournaments/$tournamentId/start")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
        )
        val items = tournamentItemJpaRepository.findAllByTournamentIdAndNotDeleted(tournamentId)
        val ti1 = items[0].getId()
        val ti2 = items[1].getId()
        mockMvc.perform(
            post("/api/v1/tournaments/$tournamentId/matches")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentRound":2,"firstTournamentItemId":$ti1,"secondTournamentItemId":$ti2,"selectedTournamentItemId":$ti1}"""),
        )
        return TournamentStart(tournamentId = tournamentId, item1Id = ti1, item2Id = ti2)
    }

    // Design B: ROOT(owner) + CLONE(member) 각 1명이 완료한 소셜 토너먼트 준비.
    // group-result 는 2명 이상 완료 시에만 조회 가능하므로 이 헬퍼로 전제 조건을 만든다.
    private fun completeSocialTournamentWith2Players(mockMvc: MockMvc): Long {
        val rootTournamentId = createTournament(mockMvc)
        // otherUserId 가 ROOT 에 멤버로 참여 (초대 코드 없이 링크 직접 접근)
        mockMvc.perform(
            post("/api/v1/tournaments/$rootTournamentId/join")
                .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"inviteCode":null}"""),
        )
        val item1Id = saveWishItem(name = "소셜아이템1")
        val item2Id = saveWishItem(name = "소셜아이템2")
        addItemsToTournament(mockMvc, rootTournamentId, userId, item1Id, item2Id)
        mockMvc.perform(
            post("/api/v1/tournaments/$rootTournamentId/start")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
        )
        val rootItems = tournamentItemJpaRepository.findAllByTournamentIdAndNotDeleted(rootTournamentId)
        val ti1 = rootItems[0].getId()
        val ti2 = rootItems[1].getId()
        // Owner 가 ROOT 결승 완료 → ROOT 즉시 COMPLETED
        mockMvc.perform(
            post("/api/v1/tournaments/$rootTournamentId/matches")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentRound":2,"firstTournamentItemId":$ti1,"secondTournamentItemId":$ti2,"selectedTournamentItemId":$ti1}"""),
        )
        // otherUserId 가 ROOT start → CLONE 생성
        val startResult = mockMvc.perform(
            post("/api/v1/tournaments/$rootTournamentId/start")
                .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId)),
        ).andReturn()
        val cloneTournamentId = objectMapper.readTree(startResult.response.contentAsString)["data"]["tournamentId"].asLong()
        // otherUserId 가 CLONE 결승 완료 (ROOT 의 아이템 ID 를 그대로 사용)
        mockMvc.perform(
            post("/api/v1/tournaments/$cloneTournamentId/matches")
                .header(HttpHeaders.AUTHORIZATION, authHeader(otherUserId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentRound":2,"firstTournamentItemId":$ti1,"secondTournamentItemId":$ti2,"selectedTournamentItemId":$ti1}"""),
        )
        return rootTournamentId
    }

    private fun startTournamentWith2Items(mockMvc: MockMvc): TournamentStart {
        val tournamentId = createTournament(mockMvc)
        addItemsToTournament(mockMvc, tournamentId, userId, saveWishItem(name = "아이템1"), saveWishItem(name = "아이템2"))
        mockMvc.perform(
            post("/api/v1/tournaments/$tournamentId/start")
                .header(HttpHeaders.AUTHORIZATION, authHeader(userId)),
        )
        val items = tournamentItemJpaRepository.findAllByTournamentIdAndNotDeleted(tournamentId)
        return TournamentStart(
            tournamentId = tournamentId,
            item1Id = items[0].getId(),
            item2Id = items[1].getId(),
        )
    }
}
