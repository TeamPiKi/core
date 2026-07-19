package com.depromeet.piki.auth.controller

import com.depromeet.piki.auth.infrastructure.oauth.OAuthException
import com.depromeet.piki.auth.infrastructure.oauth.OAuthProvider
import com.depromeet.piki.auth.infrastructure.oauth.OAuthUserInfo
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.repository.ItemSnapshotRepository
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
        val snapshotId = itemSnapshotRepository.save(ItemSnapshot.pending(itemId = 1L).apply { markProcessing() }).getId()
        wishRepository.save(Wish(userId = guestId, snapshotId = snapshotId))
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
}
