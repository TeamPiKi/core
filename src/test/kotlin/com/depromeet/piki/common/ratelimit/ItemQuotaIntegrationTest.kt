package com.depromeet.piki.common.ratelimit

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubItemParsingWorker
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.tournament.service.TournamentErrorCode
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.wishlist.domain.WishErrorCode
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

// 아이템 등록 한도(#339)의 계약 검증. 한도 자체의 산술(창 경계·잔액 판정)은 RedisItemQuotaStore 쪽 검증이
// 맡고, 여기서는 "진입점에서 무엇이 얼마나 차감되고 넘치면 어떤 응답이 나가는가" 라는 계약만 본다.
//
// DB 는 클래스 레벨 @Transactional 로 롤백되지만 **Redis 는 롤백되지 않는다.** 그래서 매 테스트가 새 UUID 를
// 써서 카운터 키를 격리한다(정리 코드를 두지 않는 원칙과 같은 해법 — 동시성 테스트가 쓰는 방식).
//
// 한도까지 실제로 등록을 반복하지 않고 카운터를 미리 채워 경계 직전 상태를 만든다. 등록 10번을 태우면
// 테스트가 느려지기만 하고 검증하는 계약은 같다.
@Transactional
class ItemQuotaIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var jwtProvider: JwtProvider

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    private lateinit var properties: ItemQuotaProperties

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var stubItemParsingWorker: StubItemParsingWorker

    @Test
    fun `위시 링크 등록이 한도를 넘으면 429 와 WISH-010 code, Retry-After 헤더를 반환한다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertUser(userId, IdentityType.MEMBER)
        // 한도를 정확히 소진한 상태 — 다음 1건이 넘긴다.
        fillQuota(ItemQuotaScope.WISH, userId, properties.wishLimit)

        mockMvc
            .perform(
                post("/api/v1/wishlists")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.MEMBER)}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"url":"https://www.musinsa.com/products/1"}"""),
            ).andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.code").value(WishErrorCode.ITEM_QUOTA_EXCEEDED.code))
            .andExpect(jsonPath("$.detail").value(WishErrorCode.ITEM_QUOTA_EXCEEDED.message))
            .andExpect(jsonPath("$.data").doesNotExist())
            // 남은 시간은 창 길이에 따라 달라지므로 값이 아니라 "양수가 실렸다" 를 계약으로 고정한다.
            .andExpect(header().exists(HttpHeaders.RETRY_AFTER))

        val retryAfter = requireNotNull(currentCount(ItemQuotaScope.WISH, userId))
        // 거부된 요청은 카운터를 올리지 않는다 — 올리면 재시도할수록 창이 끝나도 한도를 넘긴 채 시작한다.
        assertEquals(properties.wishLimit.toLong(), retryAfter)
    }

    @Test
    fun `이미지 등록은 요청 1건이 아니라 이미지 장수만큼 한도를 소모한다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertUser(userId, IdentityType.MEMBER)

        mockMvc
            .perform(
                post("/api/v1/wishlists/images/presigned")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.MEMBER)}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"contentTypes":["image/png","image/png","image/png"]}"""),
            ).andExpect(status().isOk)

        // 요청 1건이 아니라 3 이 빠져야 한다 — 장마다 추출이 따로 돌기 때문이다.
        assertEquals(3L, currentCount(ItemQuotaScope.WISH, userId))
    }

    @Test
    fun `잔액이 남아 있으면 그보다 큰 요청도 통과시키고 그 다음부터 거부한다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertUser(userId, IdentityType.MEMBER)
        // 잔액을 1 만 남긴다 — 5장 요청은 그보다 크다.
        fillQuota(ItemQuotaScope.WISH, userId, properties.wishLimit - 1)

        // 요청량은 판정에 쓰지 않으므로 통째로 통과한다. "2장만 남아서 안 됩니다" 로 막으면 사용자는 자기 잔액을
        // 모르는 채 몇 장으로 줄여야 할지도 알 수 없다 — 마지막 한 번은 성공시키고 그 다음부터 막는다.
        mockMvc
            .perform(
                post("/api/v1/wishlists/images/presigned")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.MEMBER)}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"contentTypes":["image/png","image/png","image/png","image/png","image/png"]}"""),
            ).andExpect(status().isOk)

        // 한도를 넘겨 잔액이 음수가 됐다.
        assertEquals((properties.wishLimit + 4).toLong(), currentCount(ItemQuotaScope.WISH, userId))

        // 이제부터는 크기와 무관하게 거부다.
        mockMvc
            .perform(
                post("/api/v1/wishlists")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.MEMBER)}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"url":"https://www.musinsa.com/products/9"}"""),
            ).andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.code").value(WishErrorCode.ITEM_QUOTA_EXCEEDED.code))
    }

    @Test
    fun `토너먼트 아이템 등록은 요청한 게스트가 아니라 토너먼트 오너의 몫에서 차감된다`() {
        val mockMvc = buildMockMvc()
        val ownerId = UUID.randomUUID()
        insertUser(ownerId, IdentityType.MEMBER)
        // 파싱 워커가 미커밋 item 을 집어 warn 을 쏟지 않도록 끈다(이 테스트의 관심사는 차감 귀속이다).
        stubItemParsingWorker.enabled = false

        try {
            val (tournamentId, inviteCode) = createTournament(mockMvc, ownerId)
            val guestId = joinAsGuest(mockMvc, tournamentId, inviteCode)

            mockMvc
                .perform(
                    post("/api/v1/tournaments/$tournamentId/items/link")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(guestId, IdentityType.GUEST)}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"url":"https://www.musinsa.com/products/2"}"""),
                ).andExpect(status().isOk)

            // 요청자는 게스트지만 차감은 오너 몫에서 일어난다 — 게스트 계정을 갈아타도 한도가 리셋되지 않는 근거.
            assertEquals(1L, currentCount(ItemQuotaScope.TOURNAMENT, ownerId))
            assertNull(currentCount(ItemQuotaScope.TOURNAMENT, guestId))
        } finally {
            stubItemParsingWorker.enabled = true
        }
    }

    @Test
    fun `위시 한도를 다 써도 토너먼트 아이템은 담을 수 있다`() {
        val mockMvc = buildMockMvc()
        val ownerId = UUID.randomUUID()
        insertUser(ownerId, IdentityType.MEMBER)
        fillQuota(ItemQuotaScope.WISH, ownerId, properties.wishLimit)
        stubItemParsingWorker.enabled = false

        try {
            val (tournamentId, _) = createTournament(mockMvc, ownerId)

            // 두 축은 별개 키라 위시 소진이 토너먼트를 막지 않는다 — 합쳐 두면 "친구들이 내 토너먼트에 담아서
            // 내가 내 위시를 못 쓰는" 반대 방향 사고도 함께 생긴다.
            mockMvc
                .perform(
                    post("/api/v1/tournaments/$tournamentId/items/link")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(ownerId, IdentityType.MEMBER)}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"url":"https://www.musinsa.com/products/3"}"""),
                ).andExpect(status().isOk)

            assertEquals(1L, currentCount(ItemQuotaScope.TOURNAMENT, ownerId))
        } finally {
            stubItemParsingWorker.enabled = true
        }
    }

    @Test
    fun `토너먼트 오너의 몫이 소진되면 참여 게스트의 등록이 429 와 TOURNAMENT-037 로 거부된다`() {
        val mockMvc = buildMockMvc()
        val ownerId = UUID.randomUUID()
        insertUser(ownerId, IdentityType.MEMBER)
        stubItemParsingWorker.enabled = false

        try {
            val (tournamentId, inviteCode) = createTournament(mockMvc, ownerId)
            val guestId = joinAsGuest(mockMvc, tournamentId, inviteCode)
            fillQuota(ItemQuotaScope.TOURNAMENT, ownerId, properties.tournamentLimit)

            mockMvc
                .perform(
                    post("/api/v1/tournaments/$tournamentId/items/link")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(guestId, IdentityType.GUEST)}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"url":"https://www.musinsa.com/products/4"}"""),
                ).andExpect(status().isTooManyRequests)
                .andExpect(jsonPath("$.code").value(TournamentErrorCode.ITEM_QUOTA_EXCEEDED.code))
                .andExpect(jsonPath("$.detail").value(TournamentErrorCode.ITEM_QUOTA_EXCEEDED.message))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))

            // 이 응답은 참여 게스트도 받는다. 남의(오너의) 사용량은 요청자에게 알릴 정보가 아니므로 문구가
            // 그것을 드러내지 않는지 금지 단어 부재로 고정한다 — "토너먼트가 들어있다" 같은 단언은 이 규칙과
            // 무관해서, 문구를 "오너의 남은 사용량이 0이에요" 로 바꿔도 통과해버린다.
            val message = TournamentErrorCode.ITEM_QUOTA_EXCEEDED.message
            listOf("오너", "소유자", "사용량", "남은").forEach {
                assertFalse(message.contains(it), "429 문구가 오너의 사용량을 드러낸다: $message")
            }
        } finally {
            stubItemParsingWorker.enabled = true
        }
    }

    private fun buildMockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    private fun insertUser(
        userId: UUID,
        identityType: IdentityType,
    ) {
        jdbcTemplate.update(
            "INSERT INTO users (id, nickname, identity_type, created_at, updated_at) VALUES (?, ?, ?, NOW(6), NOW(6))",
            uuidToBytes(userId),
            userId.toString().take(10),
            identityType.name,
        )
    }

    private fun token(
        userId: UUID,
        identityType: IdentityType,
    ): String = jwtProvider.generateAccessToken(userId, identityType)

    // 카운터를 미리 채워 경계 직전 상태를 만든다. 창 TTL 은 운영 경로(Lua)가 첫 차감 때 걸므로 여기서도 함께 건다 —
    // TTL 없는 키를 남기면 이후 테스트가 같은 UUID 를 재사용할 때(없지만) 영구 키가 된다.
    private fun fillQuota(
        scope: ItemQuotaScope,
        userId: UUID,
        amount: Int,
    ) {
        redisTemplate.opsForValue().set(scope.keyPrefix + userId, amount.toString(), properties.window)
    }

    private fun currentCount(
        scope: ItemQuotaScope,
        userId: UUID,
    ): Long? = redisTemplate.opsForValue().get(scope.keyPrefix + userId)?.toLong()

    private fun createTournament(
        mockMvc: MockMvc,
        ownerId: UUID,
    ): Pair<Long, String> {
        val response =
            mockMvc
                .perform(
                    post("/api/v1/tournaments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(ownerId, IdentityType.MEMBER)}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"한도 테스트 토너먼트"}"""),
                ).andExpect(status().isCreated)
                .andReturn()
                .response
                .getContentAsString(Charsets.UTF_8)
        val data = objectMapper.readTree(response).path("data")
        return data.path("tournamentId").asLong() to data.path("inviteCode").asString()
    }

    // 게스트 합류는 계정 생성까지 겸한다. 발급 토큰은 쿠키로 내려오지만 이 테스트가 필요한 것은 그 게스트의
    // userId 뿐이라, 응답에서 userId 만 읽고 헤더용 토큰은 같은 신분으로 직접 만든다(발급 토큰과 동등하다).
    private fun joinAsGuest(
        mockMvc: MockMvc,
        tournamentId: Long,
        inviteCode: String,
    ): UUID {
        val response =
            mockMvc
                .perform(
                    post("/api/v1/tournaments/$tournamentId/join/guest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"inviteCode":"$inviteCode","nickname":"한도게스트"}"""),
                ).andExpect(status().isCreated)
                .andReturn()
                .response
                .getContentAsString(Charsets.UTF_8)
        val userId =
            objectMapper
                .readTree(response)
                .path("data")
                .path("userId")
                .asString()
        return UUID.fromString(userId)
    }
}
