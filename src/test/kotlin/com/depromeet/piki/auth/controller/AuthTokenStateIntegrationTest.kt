package com.depromeet.piki.auth.controller

import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.auth.infrastructure.redis.RefreshTokenStore
import com.depromeet.piki.auth.service.AuthService
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubRefreshTokenStore
import com.depromeet.piki.support.uuidToBytes
import com.depromeet.piki.user.service.UserService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.test.assertEquals

class AuthTokenStateIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var jwtProvider: JwtProvider

    @Autowired
    private lateinit var refreshTokenStore: RefreshTokenStore

    // 같은 계정의 두 번째 기기 로그인을 재현한다 — /auth/guest 는 매번 새 유저라 계정을 공유할 수 없다.
    @Autowired
    private lateinit var authService: AuthService

    @Autowired
    private lateinit var userService: UserService

    // grace TTL 경과를 시뮬레이션하기 위해 stub 의 expireGrace 를 호출한다 (실제 Redis 는 키 TTL 만료가 함).
    @Autowired
    private lateinit var stubRefreshTokenStore: StubRefreshTokenStore

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private fun mockMvc() =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    private fun createGuest(): Pair<String, String> {
        val result =
            mockMvc()
                .perform(post("/api/v1/auth/guest").contentType(MediaType.APPLICATION_JSON).header("X-Client-Type", "app"))
                .andReturn()
        val json = objectMapper.readTree(result.response.contentAsString)
        return json.at("/data/accessToken").asText() to json.at("/data/refreshToken").asText()
    }

    private fun cleanup(userId: UUID) {
        // 세션 id 를 모르는 정리 코드라 전 세션을 지운다(#893 으로 키가 세션별로 갈렸다).
        refreshTokenStore.deleteAll(userId)
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", uuidToBytes(userId))
    }

    @Test
    fun `POST auth token refresh - 정상 rotation 흐름으로 새 refreshToken 을 연속 사용할 수 있다`() {
        // 도난 없는 정상 흐름의 baseline: R1 → R2 → R3 까지 연속 회전 가능.
        val mockMvc = mockMvc()
        val (accessToken, r1) = createGuest()
        val userId = jwtProvider.parseAccessToken(accessToken)?.userId ?: error("accessToken 파싱 실패")

        try {
            val r2 =
                objectMapper
                    .readTree(
                        mockMvc
                            .perform(
                                post("/api/v1/auth/token/refresh")
                                    .header("X-Client-Type", "app")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(mapOf("refreshToken" to r1))),
                            ).andExpect(status().isOk)
                            .andReturn()
                            .response
                            .contentAsString,
                    ).at("/data/refreshToken")
                    .asText()

            mockMvc
                .perform(
                    post("/api/v1/auth/token/refresh")
                        .header("X-Client-Type", "app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mapOf("refreshToken" to r2))),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.data.refreshToken").isString)
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `POST auth token refresh - grace 창 안에 옛 refreshToken 을 다시 쓰면 200 + 같은 새 토큰을 멱등 반환한다`() {
        // 동시 다발 refresh race 의 핵심 계약: R1 회전 직후 grace 창 안에 같은 R1 이 다시 들어오면
        // 401 로 튕기지 않고(=로그아웃 안 됨) 이미 발급된 R2 를 그대로 멱등 반환한다.
        val mockMvc = mockMvc()
        val (accessToken, r1) = createGuest()
        val userId = jwtProvider.parseAccessToken(accessToken)?.userId ?: error("accessToken 파싱 실패")

        try {
            val r1Body = objectMapper.writeValueAsString(mapOf("refreshToken" to r1))
            val r2 =
                objectMapper
                    .readTree(
                        mockMvc
                            .perform(
                                post("/api/v1/auth/token/refresh")
                                    .header("X-Client-Type", "app")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(r1Body),
                            ).andExpect(status().isOk)
                            .andReturn()
                            .response
                            .contentAsString,
                    ).at("/data/refreshToken")
                    .asText()

            // grace 창 안 R1 재사용 → 200 + 같은 R2 (멱등 replay). 회전·무효화 없음.
            val replayedR2 =
                objectMapper
                    .readTree(
                        mockMvc
                            .perform(
                                post("/api/v1/auth/token/refresh")
                                    .header("X-Client-Type", "app")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(r1Body),
                            ).andExpect(status().isOk)
                            .andReturn()
                            .response
                            .contentAsString,
                    ).at("/data/refreshToken")
                    .asText()
            assertEquals(r2, replayedR2)

            // R2 는 여전히 살아있어 다음 회전에 쓸 수 있다 (family invalidation 안 됨).
            mockMvc
                .perform(
                    post("/api/v1/auth/token/refresh")
                        .header("X-Client-Type", "app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mapOf("refreshToken" to r2))),
                ).andExpect(status().isOk)
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `POST auth token refresh - grace 밖에서 옛 refreshToken 을 재사용하면 401 + 살아있던 토큰도 무효화된다`() {
        // OAuth 2.0 RFC 6819 / 8252 의 family invalidation: grace 창이 지난 뒤 옛 R1 을 다시 쓰면
        // (= 도난 의심) 양쪽 다 끊는다. grace TTL 경과는 stub.expireGrace 로 시뮬레이션한다.
        val mockMvc = mockMvc()
        val (accessToken, r1) = createGuest()
        val userId = jwtProvider.parseAccessToken(accessToken)?.userId ?: error("accessToken 파싱 실패")

        try {
            val r1Body = objectMapper.writeValueAsString(mapOf("refreshToken" to r1))
            val r2 =
                objectMapper
                    .readTree(
                        mockMvc
                            .perform(
                                post("/api/v1/auth/token/refresh")
                                    .header("X-Client-Type", "app")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(r1Body),
                            ).andExpect(status().isOk)
                            .andReturn()
                            .response
                            .contentAsString,
                    ).at("/data/refreshToken")
                    .asText()

            // grace 창 만료
            stubRefreshTokenStore.expireGrace(userId)

            // grace 밖 R1 재사용 → 401 + family invalidation (살아있던 R2 도 같이 DEL)
            mockMvc
                .perform(
                    post("/api/v1/auth/token/refresh")
                        .header("X-Client-Type", "app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(r1Body),
                ).andExpect(status().isUnauthorized)

            // R2 도 무효화됐다 — 정상 흐름이면 200 이어야 하지만 family invalidation 으로 401
            mockMvc
                .perform(
                    post("/api/v1/auth/token/refresh")
                        .header("X-Client-Type", "app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mapOf("refreshToken" to r2))),
                ).andExpect(status().isUnauthorized)
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `POST auth logout - 로그아웃 후 refreshToken 으로 refresh 시도 시 401 이 반환된다`() {
        val mockMvc = mockMvc()
        val (accessToken, refreshToken) = createGuest()
        val userId = jwtProvider.parseAccessToken(accessToken)?.userId ?: error("accessToken 파싱 실패")

        try {
            mockMvc
                .perform(
                    post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken"),
                ).andExpect(status().isOk)

            mockMvc
                .perform(
                    post("/api/v1/auth/token/refresh")
                        .header("X-Client-Type", "app")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mapOf("refreshToken" to refreshToken))),
                ).andExpect(status().isUnauthorized)
        } finally {
            cleanup(userId)
        }
    }

    // ── 멀티 디바이스(#893) ────────────────────────────────────────────

    // 이슈가 보고한 실패 그대로다. 예전엔 키가 refresh:{userId} 하나라 B 로그인이 A 토큰을 덮어썼고,
    // 뒤늦은 A 의 갱신이 재사용으로 오인돼 family invalidation 이 돌면서 방금 로그인한 B 까지 죽었다.
    @Test
    fun `POST auth token refresh - 두 기기가 각자 로그인해도 서로의 갱신을 죽이지 않는다`() {
        val mockMvc = mockMvc()
        val (accessToken, deviceA) = createGuest()
        val userId = jwtProvider.parseAccessToken(accessToken)?.userId ?: error("accessToken 파싱 실패")

        try {
            // 같은 계정으로 두 번째 기기 로그인 — 이 시점에 A 의 슬롯이 덮이면 안 된다.
            val deviceB = authService.createTokensForUser(userService.findById(userId)).refreshToken

            // 먼저 로그인한 A 가 뒤늦게 갱신해도 정상이어야 한다.
            refresh(mockMvc, deviceA).andExpect(status().isOk)

            // A 의 갱신 때문에 B 가 무효화되지 않아야 한다 — 이게 이슈의 핵심 증상이었다.
            refresh(mockMvc, deviceB).andExpect(status().isOk)
        } finally {
            cleanup(userId)
        }
    }

    @Test
    fun `POST auth logout - 한 기기에서 로그아웃해도 다른 기기의 세션은 유지된다`() {
        val mockMvc = mockMvc()
        val (accessToken, deviceA) = createGuest()
        val userId = jwtProvider.parseAccessToken(accessToken)?.userId ?: error("accessToken 파싱 실패")

        try {
            val deviceB = authService.createTokensForUser(userService.findById(userId)).refreshToken

            // A 의 refresh 토큰을 함께 보내 "이 기기" 를 특정한다.
            mockMvc
                .perform(
                    post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
                        .content(objectMapper.writeValueAsString(mapOf("refreshToken" to deviceA))),
                ).andExpect(status().isOk)

            refresh(mockMvc, deviceA).andExpect(status().isUnauthorized)
            refresh(mockMvc, deviceB).andExpect(status().isOk)
        } finally {
            cleanup(userId)
        }
    }

    // sid 클레임이 없는 토큰 = #893 배포 이전 발급분. 세션 슬롯을 특정할 수 없어 재로그인을 유도한다.
    @Test
    fun `POST auth token refresh - sid 없는 레거시 refresh 토큰은 401 이다`() {
        val mockMvc = mockMvc()
        val (accessToken, _) = createGuest()
        val userId = jwtProvider.parseAccessToken(accessToken)?.userId ?: error("accessToken 파싱 실패")

        try {
            // sid 를 비워 배포 이전 토큰을 재현한다 (서명은 유효하다).
            val legacy = jwtProvider.generateRefreshToken(userId, "")

            refresh(mockMvc, legacy).andExpect(status().isUnauthorized)
        } finally {
            cleanup(userId)
        }
    }

    private fun refresh(
        mockMvc: MockMvc,
        refreshToken: String,
    ) = mockMvc.perform(
        post("/api/v1/auth/token/refresh")
            .header("X-Client-Type", "app")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(mapOf("refreshToken" to refreshToken))),
    )
}
