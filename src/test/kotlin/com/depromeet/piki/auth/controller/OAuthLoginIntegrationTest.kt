package com.depromeet.piki.auth.controller

import com.depromeet.piki.auth.infrastructure.oauth.OAuthException
import com.depromeet.piki.auth.infrastructure.oauth.OAuthProvider
import com.depromeet.piki.auth.infrastructure.oauth.OAuthUserInfo
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.tournament.domain.TournamentItem
import com.depromeet.piki.tournament.repository.TournamentItemRepository
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubOAuthClient
import com.depromeet.piki.user.service.WithdrawalService
import com.depromeet.piki.wishlist.domain.Wish
import com.depromeet.piki.user.repository.UserDetailRepository
import com.depromeet.piki.wishlist.repository.WishRepository
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie
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

@Transactional
class OAuthLoginIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    @Qualifier("kakaoOAuthClient")
    private lateinit var kakaoOAuthClient: StubOAuthClient

    @Autowired
    @Qualifier("googleOAuthClient")
    private lateinit var googleOAuthClient: StubOAuthClient

    @Autowired
    @Qualifier("appleOAuthClient")
    private lateinit var appleOAuthClient: StubOAuthClient

    @Autowired
    private lateinit var wishRepository: WishRepository

    @Autowired
    private lateinit var itemSnapshotRepository: ItemSnapshotRepository

    @Autowired
    private lateinit var userDetailRepository: UserDetailRepository

    @Autowired
    private lateinit var withdrawalService: WithdrawalService

    @Autowired
    private lateinit var tournamentItemRepository: TournamentItemRepository

    private fun mockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    private fun loginBody(vararg pairs: Pair<String, String>): String = objectMapper.writeValueAsString(mapOf(*pairs))

    private fun userIdOf(json: String): String = objectMapper.readTree(json).at("/data/user/id").asString()

    private data class Guest(
        val accessToken: String,
        val userId: String,
    )

    private fun createGuest(): Guest {
        val json =
            mockMvc()
                .perform(
                    post("/api/v1/auth/guest").contentType(MediaType.APPLICATION_JSON).header("X-Client-Type", "app"),
                ).andReturn()
                .response.contentAsString
        val node = objectMapper.readTree(json)
        return Guest(node.at("/data/accessToken").asString(), node.at("/data/user/id").asString())
    }

    @Test
    fun `신규 소셜 - app 으로 v2 로그인하면 MEMBER 로 가입되고 body 토큰이 온다`() {
        googleOAuthClient.fetchByAccessTokenStub =
            { OAuthUserInfo(OAuthProvider.GOOGLE, "google_fresh", "https://img/p.jpg") }

        mockMvc()
            .perform(
                post("/api/v1/auth/login/google")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Client-Type", "app")
                    .content(loginBody("accessToken" to "sdk-token")),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.user.identityType").value("MEMBER"))
            .andExpect(jsonPath("$.data.user.profileImage").value("https://img/p.jpg"))
            .andExpect(jsonPath("$.data.accessToken").isString)
            .andExpect(jsonPath("$.data.refreshToken").isString)
    }

    @Test
    fun `apple - app v2 로그인하면 provider 해석과 appleOAuthClient 빈 선택을 거쳐 MEMBER 로 가입된다`() {
        // /login/{provider} 에 apple 을 넣어 OAuthProvider.APPLE 해석 → appleOAuthClient 빈 선택까지의
        // 라우팅·와이어링을 실제로 태운다 (stub 으로 외부 Apple 호출만 격리).
        appleOAuthClient.fetchByAccessTokenStub =
            { OAuthUserInfo(OAuthProvider.APPLE, "apple_fresh", null) }

        mockMvc()
            .perform(
                post("/api/v1/auth/login/apple")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Client-Type", "app")
                    .content(loginBody("accessToken" to "apple-identity-token")),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.user.identityType").value("MEMBER"))
            .andExpect(jsonPath("$.data.accessToken").isString)
            .andExpect(jsonPath("$.data.refreshToken").isString)
    }

    @Test
    fun `재가입 - 탈퇴한 소셜로 다시 로그인하면 신규 user 가 생성된다 (tombstone 되살리지 않음)`() {
        kakaoOAuthClient.fetchByAccessTokenStub = { OAuthUserInfo(OAuthProvider.KAKAO, "kakao_rejoin", null) }
        val body = loginBody("accessToken" to "t")

        // 1. 최초 가입 → MEMBER
        val firstId =
            userIdOf(
                mockMvc()
                    .perform(
                        post(
                            "/api/v1/auth/login/kakao",
                        ).contentType(MediaType.APPLICATION_JSON).header("X-Client-Type", "app").content(body),
                    ).andReturn()
                    .response.contentAsString,
            )

        // 2. 탈퇴 (user_details 하드삭제 + tombstone). withdrawalService 직접 호출로 외부 cascade 까지 태운다.
        withdrawalService.withdraw(UUID.fromString(firstId))

        // 3. 같은 소셜로 재로그인 → user_details 가 사라졌고, 설령 tombstone 이 잡혀도 isActive 가 거르므로 신규 가입.
        val secondId =
            userIdOf(
                mockMvc()
                    .perform(
                        post(
                            "/api/v1/auth/login/kakao",
                        ).contentType(MediaType.APPLICATION_JSON).header("X-Client-Type", "app").content(body),
                    ).andExpect(status().isOk)
                    .andExpect(jsonPath("$.data.user.identityType").value("MEMBER"))
                    .andReturn()
                    .response.contentAsString,
            )

        assertNotEquals(firstId, secondId)
    }

    @Test
    fun `재방문 - 같은 소셜로 다시 로그인하면 동일 user 가 반환된다`() {
        kakaoOAuthClient.fetchByAccessTokenStub = { OAuthUserInfo(OAuthProvider.KAKAO, "kakao_return", null) }
        val body = loginBody("accessToken" to "t")

        val first =
            mockMvc()
                .perform(
                    post(
                        "/api/v1/auth/login/kakao",
                    ).contentType(MediaType.APPLICATION_JSON).header("X-Client-Type", "app").content(body),
                ).andReturn()
                .response.contentAsString
        val second =
            mockMvc()
                .perform(
                    post(
                        "/api/v1/auth/login/kakao",
                    ).contentType(MediaType.APPLICATION_JSON).header("X-Client-Type", "app").content(body),
                ).andReturn()
                .response.contentAsString

        assertEquals(userIdOf(first), userIdOf(second))
    }

    @Test
    fun `게스트 연결 - 게스트 토큰 + 신규 소셜이면 그 게스트가 MEMBER 로 승격되고 데이터를 이어받는다`() {
        val guest = createGuest()
        kakaoOAuthClient.fetchByAccessTokenStub = { OAuthUserInfo(OAuthProvider.KAKAO, "kakao_link", null) }

        mockMvc()
            .perform(
                post("/api/v1/auth/login/kakao")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Client-Type", "app")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer ${guest.accessToken}")
                    .content(loginBody("accessToken" to "t")),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.user.id").value(guest.userId))
            .andExpect(jsonPath("$.data.user.identityType").value("MEMBER"))
    }

    @Test
    fun `게스트 연결 - 승격 후에도 게스트가 만든 위시(데이터)가 그대로 승계된다`() {
        val guest = createGuest()
        val guestId = UUID.fromString(guest.userId)
        // 게스트 상태에서 위시 1건 생성 (user_id = 게스트 id). 승격은 id 를 유지하므로 이 행이 그대로 따라와야 한다.
        // wish 는 활성 snapshot 을 가리키므로(snapshotId NOT NULL) 대응 snapshot 을 먼저 시딩하고 그 id 를 넘긴다.
        val snapshotId = itemSnapshotRepository.save(ItemSnapshot.pending(itemId = 1L, requestedBy = guestId).apply { markProcessing() }).getId()
        wishRepository.save(Wish(userId = guestId, waitingSnapshotId = snapshotId, itemId = 1L))
        kakaoOAuthClient.fetchByAccessTokenStub = { OAuthUserInfo(OAuthProvider.KAKAO, "kakao_inherit", null) }

        val resultId =
            userIdOf(
                mockMvc()
                    .perform(
                        post("/api/v1/auth/login/kakao")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Client-Type", "app")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer ${guest.accessToken}")
                            .content(loginBody("accessToken" to "t")),
                    ).andExpect(status().isOk)
                    .andExpect(jsonPath("$.data.user.identityType").value("MEMBER"))
                    .andReturn()
                    .response.contentAsString,
            )

        // 승격된 멤버는 게스트와 같은 id 라, 그 id 로 만든 위시가 그대로 승계된다
        assertEquals(guest.userId, resultId)
        val wishes = wishRepository.findPage(guestId, null, 10)
        assertEquals(1, wishes.size)
        assertEquals(guestId, wishes.first().userId)
    }

    @Test
    fun `소셜 중복 - 게스트가 이미 타계정에 연결된 소셜로 로그인하면 그 기존 계정으로 로그인된다(게스트 포기)`() {
        kakaoOAuthClient.fetchByAccessTokenStub = { OAuthUserInfo(OAuthProvider.KAKAO, "kakao_dup", null) }
        val body = loginBody("accessToken" to "t")

        val userAId =
            userIdOf(
                mockMvc()
                    .perform(
                        post(
                            "/api/v1/auth/login/kakao",
                        ).contentType(MediaType.APPLICATION_JSON).header("X-Client-Type", "app").content(body),
                    ).andReturn()
                    .response.contentAsString,
            )

        val guest = createGuest()
        val resultId =
            userIdOf(
                mockMvc()
                    .perform(
                        post("/api/v1/auth/login/kakao")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Client-Type", "app")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer ${guest.accessToken}")
                            .content(body),
                    ).andExpect(status().isOk)
                    .andReturn()
                    .response.contentAsString,
            )

        assertEquals(userAId, resultId)
        assertNotEquals(guest.userId, resultId)
    }

    @Test
    fun `v1 - code+redirectUri 로 로그인된다`() {
        googleOAuthClient.fetchByCodeStub = { _, _ -> OAuthUserInfo(OAuthProvider.GOOGLE, "google_v1", null) }

        mockMvc()
            .perform(
                post("/api/v1/auth/login/google")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Client-Type", "app")
                    .content(loginBody("code" to "auth-code", "redirectUri" to "https://app/callback")),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.accessToken").isString)
    }

    @Test
    fun `잘못된 요청 - code 도 accessToken 도 없으면 400`() {
        mockMvc()
            .perform(
                post("/api/v1/auth/login/google").contentType(MediaType.APPLICATION_JSON).content("{}"),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `잘못된 요청 - accessToken 과 code 를 동시에 보내면 400`() {
        mockMvc()
            .perform(
                post("/api/v1/auth/login/google")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginBody("accessToken" to "t", "code" to "c", "redirectUri" to "https://app/callback")),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `미지원 provider - facebook 은 400`() {
        mockMvc()
            .perform(
                post("/api/v1/auth/login/facebook").contentType(MediaType.APPLICATION_JSON).content(
                    loginBody(
                        "accessToken" to "t",
                    ),
                ),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `provider 호출 실패 - 502 Bad Gateway 로 매핑된다`() {
        googleOAuthClient.fetchByAccessTokenStub = { error("google down") }

        mockMvc()
            .perform(
                post("/api/v1/auth/login/google").contentType(MediaType.APPLICATION_JSON).content(
                    loginBody(
                        "accessToken" to "t",
                    ),
                ),
            ).andExpect(status().isBadGateway)
            .andExpect(jsonPath("$.code").value("OAUTH-001"))
            .andExpect(jsonPath("$.detail").value("로그인에 실패했어요. 잠시 후 다시 시도해 주세요."))
            .andExpect(jsonPath("$.data").value(nullValue()))
    }

    @Test
    fun `provider access token 무효 - invalidProviderToken 은 401 로 매핑된다`() {
        googleOAuthClient.fetchByAccessTokenStub = { throw OAuthException.invalidProviderToken() }

        mockMvc()
            .perform(
                post("/api/v1/auth/login/google").contentType(MediaType.APPLICATION_JSON).content(
                    loginBody(
                        "accessToken" to "t",
                    ),
                ),
            ).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("OAUTH-006"))
            .andExpect(jsonPath("$.detail").value("로그인 정보가 만료됐어요. 다시 로그인해 주세요."))
            .andExpect(jsonPath("$.data").value(nullValue()))
    }

    @Test
    fun `인가 정보 만료-무효 - invalidGrant 는 400 으로 매핑된다`() {
        // invalidGrant 는 access token 실패가 아니라 인가코드(code) 교환 실패다 —
        // v1 code+redirectUri 경로(fetchUserInfoByCode)로 실제 분기를 태워 검증한다.
        googleOAuthClient.fetchByCodeStub = { _, _ -> throw OAuthException.invalidGrant() }

        mockMvc()
            .perform(
                post("/api/v1/auth/login/google").contentType(MediaType.APPLICATION_JSON).content(
                    loginBody(
                        "code" to "expired-code",
                        "redirectUri" to "https://app/callback",
                    ),
                ),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("OAUTH-005"))
            .andExpect(jsonPath("$.detail").value("로그인 정보가 만료됐어요. 다시 시도해 주세요."))
            .andExpect(jsonPath("$.data").value(nullValue()))
    }

    @Test
    fun `OAuth 설정 오류 - misconfigured 는 500 OAUTH-007 로 매핑된다`() {
        // 우리 OAuth 설정 오류(invalid_client 등)는 상류 장애가 아니라 우리 서버 버그라 500 + SERVER_ERROR(OAUTH-007).
        // provider 일시 장애(OAUTH-001, 502 RETRYABLE)와는 code·status 로 구분된다.
        googleOAuthClient.fetchByAccessTokenStub =
            { throw OAuthException.misconfigured(RuntimeException("client secret invalid")) }

        mockMvc()
            .perform(
                post("/api/v1/auth/login/google").contentType(MediaType.APPLICATION_JSON).content(
                    loginBody(
                        "accessToken" to "t",
                    ),
                ),
            ).andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.code").value("OAUTH-007"))
            .andExpect(jsonPath("$.detail").value("로그인에 실패했어요. 잠시 후 다시 시도해 주세요."))
            .andExpect(jsonPath("$.data").value(nullValue()))
    }

    @Test
    fun `기본(헤더 없음) - 토큰을 쿠키로 내리고 body 토큰은 null 이다`() {
        googleOAuthClient.fetchByAccessTokenStub = { OAuthUserInfo(OAuthProvider.GOOGLE, "google_web", null) }

        mockMvc()
            .perform(
                post("/api/v1/auth/login/google").contentType(MediaType.APPLICATION_JSON).content(
                    loginBody(
                        "accessToken" to "t",
                    ),
                ),
            ).andExpect(status().isOk)
            .andExpect(cookie().exists("access_token"))
            .andExpect(cookie().exists("refresh_token"))
            .andExpect(jsonPath("$.data.accessToken").value(nullValue()))
            .andExpect(jsonPath("$.data.refreshToken").value(nullValue()))
    }

    @Test
    fun `구글 신규 로그인 시 user_details 에 email 이 저장된다`() {
        googleOAuthClient.fetchByAccessTokenStub =
            { OAuthUserInfo(OAuthProvider.GOOGLE, "google_email", "https://img/p.jpg", email = "user@gmail.com") }

        mockMvc()
            .perform(
                post("/api/v1/auth/login/google")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Client-Type", "app")
                    .content(loginBody("accessToken" to "t")),
            ).andExpect(status().isOk)

        val detail = userDetailRepository.findByProviderAndSocialId("GOOGLE", "google_email")
        assertEquals("user@gmail.com", detail?.email)
    }

    @Test
    fun `애플 로그인 시 id_token email 클레임이 없으면 user_details email 은 null 이다`() {
        appleOAuthClient.fetchByAccessTokenStub =
            { OAuthUserInfo(OAuthProvider.APPLE, "apple_noemail", null, email = null) }

        mockMvc()
            .perform(
                post("/api/v1/auth/login/apple")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Client-Type", "app")
                    .content(loginBody("accessToken" to "t")),
            ).andExpect(status().isOk)

        val detail = userDetailRepository.findByProviderAndSocialId("APPLE", "apple_noemail")
        assertEquals(null, detail?.email)
    }

    @Test
    fun `기존 유저 재로그인 시 email 이 backfill 갱신된다`() {
        val body = loginBody("accessToken" to "t")
        // 1차: email 없이 가입
        googleOAuthClient.fetchByAccessTokenStub =
            { OAuthUserInfo(OAuthProvider.GOOGLE, "google_backfill", null, email = null) }
        mockMvc()
            .perform(
                post("/api/v1/auth/login/google")
                    .contentType(MediaType.APPLICATION_JSON).header("X-Client-Type", "app").content(body),
            ).andExpect(status().isOk)

        // 2차: provider 가 email 을 주면 재로그인에서 backfill
        googleOAuthClient.fetchByAccessTokenStub =
            { OAuthUserInfo(OAuthProvider.GOOGLE, "google_backfill", null, email = "filled@gmail.com") }
        mockMvc()
            .perform(
                post("/api/v1/auth/login/google")
                    .contentType(MediaType.APPLICATION_JSON).header("X-Client-Type", "app").content(body),
            ).andExpect(status().isOk)

        val detail = userDetailRepository.findByProviderAndSocialId("GOOGLE", "google_backfill")
        assertEquals("filled@gmail.com", detail?.email)
    }

    @Test
    fun `재로그인 시 email 이 null 로 오면 기존 값을 유지한다`() {
        val body = loginBody("accessToken" to "t")
        // 1차: email 있게 가입
        googleOAuthClient.fetchByAccessTokenStub =
            { OAuthUserInfo(OAuthProvider.GOOGLE, "google_keep", null, email = "keep@gmail.com") }
        mockMvc()
            .perform(
                post("/api/v1/auth/login/google")
                    .contentType(MediaType.APPLICATION_JSON).header("X-Client-Type", "app").content(body),
            ).andExpect(status().isOk)

        // 2차: email 미제공(애플 2회차 등) → 기존 값 보존
        googleOAuthClient.fetchByAccessTokenStub =
            { OAuthUserInfo(OAuthProvider.GOOGLE, "google_keep", null, email = null) }
        mockMvc()
            .perform(
                post("/api/v1/auth/login/google")
                    .contentType(MediaType.APPLICATION_JSON).header("X-Client-Type", "app").content(body),
            ).andExpect(status().isOk)

        val detail = userDetailRepository.findByProviderAndSocialId("GOOGLE", "google_keep")
        assertEquals("keep@gmail.com", detail?.email)
    }

    // ── 게스트 플레이 승계(#1081) ────────────────────────────────────────

    private fun bearer(token: String) = "Bearer $token"

    private fun createTournament(
        accessToken: String,
        name: String,
    ): Long {
        val json =
            mockMvc()
                .perform(
                    post("/api/v1/tournaments")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"$name"}"""),
                ).andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        return objectMapper.readTree(json).at("/data/tournamentId").asLong()
    }

    private fun loginWithGuestToken(
        guestAccessToken: String,
        body: String,
    ): String =
        mockMvc()
            .perform(
                post("/api/v1/auth/login/kakao")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Client-Type", "app")
                    .header(HttpHeaders.AUTHORIZATION, bearer(guestAccessToken))
                    .content(body),
            ).andExpect(status().isOk)
            .andReturn()
            .response.contentAsString

    @Test
    fun `게스트가 이미 회원인 소셜로 로그인하면 게스트로 하던 토너먼트가 회원 계정으로 넘어온다`() {
        // 이 소셜에 회원 계정이 이미 있으면 로그인은 "게스트 포기" 갈래를 탄다 — 승격과 달리 userId 가 바뀌어
        // 게스트로 하던 판이 회원에겐 "참여자가 아님"(403)이 된다. 승계가 그 구멍을 메운다(#1081).
        kakaoOAuthClient.fetchByAccessTokenStub = { OAuthUserInfo(OAuthProvider.KAKAO, "kakao_takeover", null) }
        val body = loginBody("accessToken" to "t")

        // 1. 그 소셜로 이미 가입해 둔 회원이 있다.
        val firstLogin = objectMapper.readTree(
            mockMvc()
                .perform(
                    post("/api/v1/auth/login/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Client-Type", "app")
                        .content(body),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString,
        )
        val memberId = firstLogin.at("/data/user/id").asString()

        // 2. 게스트로 토너먼트를 만들고(= 게스트 참여 행), 상품도 하나 담아 둔다(= 게스트가 주인인 출전 아이템).
        val guest = createGuest()
        val guestId = UUID.fromString(guest.userId)
        val tournamentId = createTournament(guest.accessToken, "게스트가 만든 토너먼트")
        val snapshotId =
            itemSnapshotRepository
                .save(ItemSnapshot.pending(itemId = 9001L, requestedBy = guestId).apply { markProcessing() })
                .getId()
        tournamentItemRepository.save(TournamentItem(tournamentId = tournamentId, userId = guestId, snapshotId = snapshotId))

        // 3. 게스트 토큰을 들고 같은 소셜로 로그인 → 게스트를 버리고 기존 회원으로 합류한다.
        val loginJson = loginWithGuestToken(guest.accessToken, body)
        val loggedInId = objectMapper.readTree(loginJson).at("/data/user/id").asString()
        assertEquals(memberId, loggedInId, "게스트가 아니라 기존 회원으로 로그인돼야 이 시나리오가 성립한다")
        assertNotEquals(guest.userId, loggedInId)

        // 4. 그 회원 토큰으로 방금까지 하던 토너먼트가 열린다 — 승계 전이면 403 이다.
        val memberToken = objectMapper.readTree(loginJson).at("/data/accessToken").asString()
        mockMvc()
            .perform(get("/api/v1/tournaments/$tournamentId").header(HttpHeaders.AUTHORIZATION, bearer(memberToken)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.isOwner").value(true))
            // 게스트가 담은 상품의 주인도 함께 넘어온다. 안 옮기면 내가 담은 상품이 죽은 계정을 가리켜
            // 참가자별 itemCount 가 어긋난다 — 아이템 이관이 빠져도 위 단언은 통과하므로 따로 못박는다.
            .andExpect(jsonPath("$.data.pending.items[0].userId").value(memberId))
            .andExpect(jsonPath("$.data.pending.participants[0].itemCount").value(1))
    }

    @Test
    fun `게스트가 방장인 방이 충돌하면 방장 자리를 회원에게 넘기고 게스트 행을 접는다`() {
        // 방장 지정은 tournaments.owner_tournament_user_id 가 참여 행 id 를 직접 가리키는 구조라,
        // 게스트 방장 행을 그냥 접으면 아무도 방장이 아닌 방이 남아 시작·플레이링크가 전부 막힌다.
        kakaoOAuthClient.fetchByAccessTokenStub = { OAuthUserInfo(OAuthProvider.KAKAO, "kakao_host_conflict", null) }
        val body = loginBody("accessToken" to "t")

        val firstLogin = objectMapper.readTree(
            mockMvc()
                .perform(
                    post("/api/v1/auth/login/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Client-Type", "app")
                        .content(body),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString,
        )
        val memberId = firstLogin.at("/data/user/id").asString()
        val memberTokenBefore = firstLogin.at("/data/accessToken").asString()

        // 게스트가 방을 만들어 방장이 되고, 그 방에 회원도 참여한다 → 승계 시 충돌 방이 된다.
        val guest = createGuest()
        val tournamentId = createTournament(guest.accessToken, "게스트가 방장인 방")
        mockMvc()
            .perform(
                post("/api/v1/tournaments/$tournamentId/join")
                    .header(HttpHeaders.AUTHORIZATION, bearer(memberTokenBefore))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"inviteCode":null}"""),
            ).andExpect(status().isOk)

        val loginJson = loginWithGuestToken(guest.accessToken, body)
        val memberToken = objectMapper.readTree(loginJson).at("/data/accessToken").asString()

        mockMvc()
            .perform(get("/api/v1/tournaments/$tournamentId").header(HttpHeaders.AUTHORIZATION, bearer(memberToken)))
            .andExpect(status().isOk)
            // 방장 자리가 회원에게 넘어왔다 — 안 넘기면 false 가 되어 방장 전용 동작이 전부 막힌다.
            .andExpect(jsonPath("$.data.isOwner").value(true))
            .andExpect(jsonPath("$.data.pending.participants.length()").value(1))
            .andExpect(jsonPath("$.data.pending.participants[0].userId").value(memberId))
            .andExpect(jsonPath("$.data.pending.participants[0].isHost").value(true))
    }

    @Test
    fun `회원이 이미 참여한 토너먼트는 승계에서 건너뛰고 나머지는 넘어온다`() {
        // uk_tournament_users(tournament_id, user_id) 충돌 케이스. 회원 행을 정본으로 두고 게스트 행은 접는다.
        // 충돌 하나 때문에 무관한 토너먼트까지 잃으면 안 되므로, 건너뛴 방 외에는 정상 이관돼야 한다.
        kakaoOAuthClient.fetchByAccessTokenStub = { OAuthUserInfo(OAuthProvider.KAKAO, "kakao_conflict", null) }
        val body = loginBody("accessToken" to "t")

        val firstLogin = objectMapper.readTree(
            mockMvc()
                .perform(
                    post("/api/v1/auth/login/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Client-Type", "app")
                        .content(body),
                ).andExpect(status().isOk)
                .andReturn()
                .response.contentAsString,
        )
        val memberTokenBefore = firstLogin.at("/data/accessToken").asString()

        // 회원이 만든 토너먼트에 게스트도 참여해 둔다 → 같은 방에 두 행.
        val sharedId = createTournament(memberTokenBefore, "둘 다 참여한 방")
        val guest = createGuest()
        mockMvc()
            .perform(
                post("/api/v1/tournaments/$sharedId/join")
                    .header(HttpHeaders.AUTHORIZATION, bearer(guest.accessToken))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"inviteCode":null}"""),
            ).andExpect(status().isOk)
        // 충돌과 무관한 게스트 전용 토너먼트도 하나 만든다.
        val guestOnlyId = createTournament(guest.accessToken, "게스트만 있는 방")

        val loginJson = loginWithGuestToken(guest.accessToken, body)
        val memberToken = objectMapper.readTree(loginJson).at("/data/accessToken").asString()

        // 충돌한 방은 회원 행이 살아 있어 그대로 열리고, 게스트 전용 방도 승계돼 열린다.
        mockMvc()
            .perform(get("/api/v1/tournaments/$sharedId").header(HttpHeaders.AUTHORIZATION, bearer(memberToken)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.isOwner").value(true))
            // 게스트 행이 접혀 참여자가 회원 하나만 남는다 — 안 접으면 같은 사람이 둘로 보인다.
            .andExpect(jsonPath("$.data.pending.participants.length()").value(1))
        mockMvc()
            .perform(get("/api/v1/tournaments/$guestOnlyId").header(HttpHeaders.AUTHORIZATION, bearer(memberToken)))
            .andExpect(status().isOk)
    }
}
