package com.depromeet.piki.common.ratelimit

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.common.exception.CommonErrorCode
import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.repository.ItemJpaRepository
import com.depromeet.piki.item.repository.ItemSnapshotJpaRepository
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubImageParsingWorker
import com.depromeet.piki.support.StubImageStorage
import com.depromeet.piki.support.StubItemParsingWorker
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.tournament.service.TournamentErrorCode
import com.depromeet.piki.user.domain.IdentityType
import com.depromeet.piki.wishlist.domain.Wish
import com.depromeet.piki.wishlist.domain.WishErrorCode
import com.depromeet.piki.wishlist.repository.WishJpaRepository
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
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
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    private lateinit var settings: ItemQuotaSettings

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var stubItemParsingWorker: StubItemParsingWorker

    @Autowired
    private lateinit var stubImageParsingWorker: StubImageParsingWorker

    @Autowired
    private lateinit var stubImageStorage: StubImageStorage

    @Autowired
    private lateinit var itemJpaRepository: ItemJpaRepository

    @Autowired
    private lateinit var itemSnapshotJpaRepository: ItemSnapshotJpaRepository

    @Autowired
    private lateinit var wishJpaRepository: WishJpaRepository

    @Test
    fun `위시 링크 등록이 한도를 넘으면 429 와 WISH-010 code, Retry-After 헤더를 반환한다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertUser(userId, IdentityType.MEMBER)
        // 한도를 정확히 소진한 상태 — 다음 1건이 넘긴다.
        fillQuota(userId, settings.current().userLimit)

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

        // 거부된 요청은 카운터를 올리지 않는다 — 올리면 재시도할수록 창이 끝나도 한도를 넘긴 채 시작한다.
        assertEquals(settings.current().userLimit.toLong(), currentCount(userId))
    }

    @Test
    fun `전역 가용량이 경고선을 넘으면 알림 룰이 매칭하는 형식으로 경고 로그를 남긴다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertUser(userId, IdentityType.MEMBER)
        // 경고선 직전까지 채운다 — 이 등록 1건이 경계를 넘긴다.
        redisTemplate
            .opsForValue()
            .set(
                RedisItemQuotaStore.CAPACITY_KEY,
                (settings.current().capacityAlertThreshold - 1).toString(),
                settings.current().window,
            )
        // Loki 가 실제로 보는 것은 렌더된 메시지 한 줄이므로, 그 줄을 그대로 받아 형식을 검사한다.
        val logger = LoggerFactory.getLogger(ItemQuotaGuard::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)

        try {
            mockMvc
                .perform(
                    post("/api/v1/wishlists")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.MEMBER)}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"url":"https://www.musinsa.com/products/13"}"""),
                ).andExpect(status().isCreated)

            val warned =
                appender.list
                    .filter { it.level == Level.WARN }
                    .map { it.formattedMessage }
                    .filter { it.startsWith(ItemQuotaGuard.CAPACITY_ALERT_EVENT) }
            assertEquals(1, warned.size, "경고선을 넘긴 요청은 경고를 정확히 한 줄 남겨야 한다: ${appender.list.map { it.formattedMessage }}")

            // 이 형식이 곧 알림 계약이다. 한국어 산문으로 되돌아가거나 필드가 logfmt(`키=값`)를 벗어나면
            // Loki 룰이 매칭에 실패해 **알림이 조용히 죽는다** — 안 울리는 것은 정상 상태와 구분되지 않는다.
            // windowSeconds 가 숫자가 아니게 되는 회귀(Duration.toString 의 PT1H)도 여기서 걸린다.
            assertTrue(
                ALERT_LINE_FORMAT.matches(warned.single()),
                "경고 로그가 알림 룰이 매칭하는 형식이 아니다: ${warned.single()}",
            )
        } finally {
            logger.detachAppender(appender)
            // 전역 카운터는 서비스에 하나뿐이라 UUID 로 격리할 수 없다(아래 503 테스트와 같은 이유).
            redisTemplate.delete(RedisItemQuotaStore.CAPACITY_KEY)
        }
    }

    @Test
    fun `전역 가용량이 소진되면 자기 몫이 남아 있어도 503 과 SERVER-BUSY code, Retry-After 헤더를 반환한다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertUser(userId, IdentityType.MEMBER)
        // 이 사용자는 자기 몫을 한 건도 쓰지 않았다. 그래도 막힌다는 것이 이 축의 존재 이유다 —
        // 계정별 한도는 "한 사람이 100번" 을 막지만 "100명이 각자 10번" 은 막지 못한다.
        fillCapacity()

        try {
            mockMvc
                .perform(
                    post("/api/v1/wishlists")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.MEMBER)}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"url":"https://www.musinsa.com/products/9"}"""),
                ).andExpect(status().isServiceUnavailable)
                // 사용자 잘못이 아니라 서비스가 꽉 찬 것이라 4xx 가 아니고, 도메인 code 도 아니다.
                .andExpect(jsonPath("$.code").value(CommonErrorCode.SERVER_BUSY.code))
                .andExpect(jsonPath("$.detail").value(CommonErrorCode.SERVER_BUSY.message))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))

            // 전역에서 막힌 요청은 요청자의 몫을 건드리지 않는다. 깎으면 안내대로 재시도할 때마다 자기 몫을 잃고,
            // 가용량이 회복된 뒤에도 자기 한도에 걸려 429 를 받게 된다.
            assertNull(currentCount(userId))
        } finally {
            // 전역 카운터는 서비스에 하나뿐이라 UUID 로 격리할 수 없다. 지우지 않으면 같은 Redis 를 쓰는
            // 이후 등록 테스트가 전부 503 으로 깨진다(Redis 는 @Transactional 롤백 대상이 아니다).
            redisTemplate.delete(RedisItemQuotaStore.CAPACITY_KEY)
        }
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
        assertEquals(3L, currentCount(userId))
    }

    @Test
    fun `이미지 등록은 presign 에서만 차감하고 confirm 은 추가로 차감하지 않는다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertUser(userId, IdentityType.MEMBER)
        stubImageParsingWorker.enabled = false
        // 공유 stub 이라 이 테스트가 쓰는 동작을 명시 세팅한다 — "업로드가 끝났다"(exists=true)가 confirm 의 전제다.
        stubImageStorage.existsBehavior = stubImageStorage.defaultExistsBehavior

        try {
            val response =
                mockMvc
                    .perform(
                        post("/api/v1/wishlists/images/presigned")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.MEMBER)}")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"contentTypes":["image/png","image/jpeg"]}"""),
                    ).andExpect(status().isOk)
                    .andReturn()
                    .response
                    .getContentAsString(Charsets.UTF_8)
            val uploads = objectMapper.readTree(response).path("data").path("uploads")
            val keys = listOf(uploads.path(0).path("imageKey").asString(), uploads.path(1).path("imageKey").asString())
            assertEquals(2L, currentCount(userId))

            mockMvc
                .perform(
                    post("/api/v1/wishlists/images/confirm")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.MEMBER)}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mapOf("imageKeys" to keys))),
                ).andExpect(status().isCreated)

            // 발급 시점에 이미 깎았으므로 확정은 0 이다. 여기서 또 깎으면 이미지 한 장이 두 번 세어진다.
            assertEquals(2L, currentCount(userId))
        } finally {
            stubImageParsingWorker.enabled = true
        }
    }

    @Test
    fun `잔액이 남아 있으면 그보다 큰 요청도 통과시키고 그 다음부터 거부한다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertUser(userId, IdentityType.MEMBER)
        // 잔액을 1 만 남긴다 — 5장 요청은 그보다 크다.
        fillQuota(userId, settings.current().userLimit - 1)

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
        assertEquals((settings.current().userLimit + 4).toLong(), currentCount(userId))

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
            assertEquals(1L, currentCount(ownerId))
            assertNull(currentCount(guestId))
        } finally {
            stubItemParsingWorker.enabled = true
        }
    }

    @Test
    fun `위시로 몫을 다 쓰면 같은 계정의 토너먼트 아이템 추가도 막힌다`() {
        val mockMvc = buildMockMvc()
        val ownerId = UUID.randomUUID()
        insertUser(ownerId, IdentityType.MEMBER)
        fillQuota(ownerId, settings.current().userLimit)
        stubItemParsingWorker.enabled = false

        try {
            val (tournamentId, _) = createTournament(mockMvc, ownerId)

            // 몫은 경로별이 아니라 계정 하나짜리다. 한때 위시·토너먼트를 별개 축으로 나눠 이 요청이 통과했는데,
            // 그러면 한 계정의 실제 상한이 두 한도의 합이 되어 "이 계정이 시간당 얼마나 쓰나" 를 한 숫자로 말할 수 없다.
            mockMvc
                .perform(
                    post("/api/v1/tournaments/$tournamentId/items/link")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(ownerId, IdentityType.MEMBER)}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"url":"https://www.musinsa.com/products/3"}"""),
                ).andExpect(status().isTooManyRequests)
                // 카운터는 하나지만 응답 code 는 경로가 소유한다 — 토너먼트에서 막혔으면 토너먼트 code 다.
                .andExpect(jsonPath("$.code").value(TournamentErrorCode.ITEM_QUOTA_EXCEEDED.code))
        } finally {
            stubItemParsingWorker.enabled = true
        }
    }

    @Test
    fun `위시 등록과 토너먼트 추가가 같은 카운터를 함께 쓴다`() {
        val mockMvc = buildMockMvc()
        val ownerId = UUID.randomUUID()
        insertUser(ownerId, IdentityType.MEMBER)
        stubItemParsingWorker.enabled = false

        try {
            val (tournamentId, _) = createTournament(mockMvc, ownerId)

            mockMvc
                .perform(
                    post("/api/v1/wishlists")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(ownerId, IdentityType.MEMBER)}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"url":"https://www.musinsa.com/products/10"}"""),
                ).andExpect(status().isCreated)
            mockMvc
                .perform(
                    post("/api/v1/tournaments/$tournamentId/items/link")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(ownerId, IdentityType.MEMBER)}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"url":"https://www.musinsa.com/products/11"}"""),
                ).andExpect(status().isOk)

            // 두 경로가 각자 카운터를 가지면 여기서 1 과 1 이 되어 이 단언이 깨진다.
            assertEquals(2L, currentCount(ownerId))
        } finally {
            stubItemParsingWorker.enabled = true
        }
    }

    @Test
    fun `위시에 있는 아이템을 토너먼트로 담는 것은 몫을 쓰지 않는다`() {
        val mockMvc = buildMockMvc()
        val ownerId = UUID.randomUUID()
        insertUser(ownerId, IdentityType.MEMBER)
        // 등록 API 를 태우지 않고 READY 위시를 바로 만든다 — 등록분 차감을 섞지 않아야 "이동이 0" 인지가 선명하다.
        // (출전은 활성 snapshot 이 READY 인 item 만 허용하므로 PENDING 인 갓 등록분으로는 이 경로를 탈 수 없다.)
        val itemId = insertReadyWish(ownerId)
        val (tournamentId, _) = createTournament(mockMvc, ownerId)

        mockMvc
            .perform(
                post("/api/v1/tournaments/$tournamentId/items/wish")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(ownerId, IdentityType.MEMBER)}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"itemIds":[$itemId]}"""),
            ).andExpect(status().isOk)

        // 이동은 이미 있는 item 을 참조만 할 뿐 새 파싱이 없다. 여기서 깎으면 같은 상품이 두 번 세어진다
        // (그 item 은 위시에 담길 때 이미 한 번 깎였다).
        assertNull(currentCount(ownerId))
    }

    @Test
    fun `이미 담은 상품을 다시 등록하면 몫을 쓰지 않고 409 에 그 위시 id 를 함께 내린다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertUser(userId, IdentityType.MEMBER)
        // 파싱이 돌면 canonical 확정·병합이 끼어들어 정체성 판정이 흔들린다 — 등록 시점 별칭만으로 판정되게 꺼 둔다.
        stubItemParsingWorker.enabled = false
        val url = "https://www.musinsa.com/products/8100004"
        try {
            val created =
                mockMvc
                    .perform(
                        post("/api/v1/wishlists")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.MEMBER)}")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"url":"$url"}"""),
                    ).andExpect(status().isCreated)
                    .andReturn()
            val wishId =
                objectMapper
                    .readTree(created.response.getContentAsString(Charsets.UTF_8))
                    .path("data")
                    .path("wish")
                    .path("id")
                    .asLong()
            // 첫 등록은 새 파싱을 만드니 정상적으로 1 을 쓴다.
            assertEquals(1L, currentCount(userId))

            // 응답이 유실된 뒤의 재시도와 같은 모양 — 클라는 담겼는지 모른 채 같은 URL 을 다시 보낸다.
            mockMvc
                .perform(
                    post("/api/v1/wishlists")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.MEMBER)}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"url":"$url"}"""),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value(WishErrorCode.ALREADY_EXISTS.code))
                // 사유만으로는 어느 위시인지 알 수 없어 목록을 다시 조회해야 했다 — 그 위시를 바로 가리킨다.
                .andExpect(jsonPath("$.data.wishId").value(wishId))

            // 핵심: 담기지 않은 요청이 몫을 깎으면 사용자는 재시도할수록 한도만 잃는다.
            assertEquals(1L, currentCount(userId))
        } finally {
            stubItemParsingWorker.enabled = true
        }
    }

    @Test
    fun `이미 담은 상품은 몫이 소진돼 있어도 429 가 아니라 409 로 거부된다`() {
        val mockMvc = buildMockMvc()
        val userId = UUID.randomUUID()
        insertUser(userId, IdentityType.MEMBER)
        stubItemParsingWorker.enabled = false
        val url = "https://www.musinsa.com/products/8100006"
        try {
            mockMvc
                .perform(
                    post("/api/v1/wishlists")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.MEMBER)}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"url":"$url"}"""),
                ).andExpect(status().isCreated)
            // 등록 뒤에 몫을 소진시킨다 — 이 상태에서 같은 상품을 다시 보내면 두 사유(중복·한도)가 동시에 성립한다.
            fillQuota(userId, settings.current().userLimit)

            // 중복은 한도와 무관한 사실이라 그쪽이 먼저 답이다. 한도를 먼저 보면 "담을 수 있었는데 몫이 없다"는
            // 잘못된 안내(429 + Retry-After)가 나가고, 창이 지나 재시도해도 결국 409 다.
            mockMvc
                .perform(
                    post("/api/v1/wishlists")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(userId, IdentityType.MEMBER)}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"url":"$url"}"""),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value(WishErrorCode.ALREADY_EXISTS.code))
        } finally {
            stubItemParsingWorker.enabled = true
        }
    }

    @Test
    fun `이미 담긴 링크를 토너먼트에 다시 추가하면 오너 몫을 쓰지 않고 409 에 그 아이템 id 를 함께 내린다`() {
        val mockMvc = buildMockMvc()
        val ownerId = UUID.randomUUID()
        insertUser(ownerId, IdentityType.MEMBER)
        stubItemParsingWorker.enabled = false
        val url = "https://www.musinsa.com/products/8100005"
        try {
            val (tournamentId, _) = createTournament(mockMvc, ownerId)
            val added =
                mockMvc
                    .perform(
                        post("/api/v1/tournaments/$tournamentId/items/link")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(ownerId, IdentityType.MEMBER)}")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"url":"$url"}"""),
                    ).andExpect(status().isOk)
                    .andReturn()
            val tournamentItemId =
                objectMapper
                    .readTree(added.response.getContentAsString(Charsets.UTF_8))
                    .path("data")
                    .path("tournamentItemId")
                    .asLong()
            assertEquals(1L, currentCount(ownerId))

            mockMvc
                .perform(
                    post("/api/v1/tournaments/$tournamentId/items/link")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer ${token(ownerId, IdentityType.MEMBER)}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"url":"$url"}"""),
                ).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value(TournamentErrorCode.DUPLICATE_TOURNAMENT_ITEM.code))
                .andExpect(jsonPath("$.data.tournamentItemId").value(tournamentItemId))

            assertEquals(1L, currentCount(ownerId))
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
            fillQuota(ownerId, settings.current().userLimit)

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
        userId: UUID,
        amount: Int,
    ) {
        redisTemplate
            .opsForValue()
            .set(RedisItemQuotaStore.USER_KEY_PREFIX + userId, amount.toString(), settings.current().window)
    }

    private fun currentCount(userId: UUID): Long? =
        redisTemplate.opsForValue().get(RedisItemQuotaStore.USER_KEY_PREFIX + userId)?.toLong()

    // 파싱이 끝난(READY) 위시 항목을 등록 API 없이 바로 만든다 — 토너먼트 출전은 활성 snapshot 이 READY 인
    // item 만 허용하므로, 등록 API 로 만든 PENDING 항목으로는 이동 경로를 탈 수 없다. itemId 를 돌려준다.
    private fun insertReadyWish(userId: UUID): Long {
        val item = itemJpaRepository.save(Item())
        val snapshot =
            itemSnapshotJpaRepository.save(
                ItemSnapshot(
                    itemId = item.getId(),
                    name = "한도 테스트 아이템",
                    price = 10_000,
                    currency = "KRW",
                    status = ItemStatus.READY,
                    extractedAt = LocalDateTime.now(),
                ),
            )
        wishJpaRepository.save(Wish(userId = userId, snapshotId = snapshot.getId()))
        return item.getId()
    }

    // 전역 카운터를 상한까지 채워 "서비스가 꽉 찬" 상태를 만든다. 부르는 테스트가 끝에서 반드시 키를 지운다.
    private fun fillCapacity() {
        val quota = settings.current()
        redisTemplate
            .opsForValue()
            .set(RedisItemQuotaStore.CAPACITY_KEY, quota.capacityLimit.toString(), quota.window)
    }

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

    companion object {
        // Loki 룰이 `|= "item.quota.capacity.alert" | logfmt` 로 집는 형식. 고정 이벤트 키로 시작하고
        // 나머지가 전부 `키=숫자` 여야 필드가 라벨로 추출돼 Discord 문구에 실린다.
        private val ALERT_LINE_FORMAT =
            Regex("""^item\.quota\.capacity\.alert used=\d+ threshold=\d+ limit=\d+ windowSeconds=\d+$""")
    }
}
