package com.depromeet.piki.auth.controller

import com.depromeet.piki.support.IntegrationTestSupport
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertNotEquals

@Transactional
class AuthControllerIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private fun mockMvc() =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    @Test
    fun `POST auth guest - 201 과 accessToken, refreshToken, user 가 응답에 포함된다`() {
        // FE 의 진입 fill 흐름을 위해 user 정보가 응답에 같이 와야 한다. 회귀 가드.
        mockMvc()
            .perform(post("/api/v1/auth/guest").contentType(MediaType.APPLICATION_JSON).header("X-Client-Type", "app"))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.accessToken").isString)
            .andExpect(jsonPath("$.data.refreshToken").isString)
            .andExpect(jsonPath("$.data.user.id").isString)
            .andExpect(jsonPath("$.data.user.nickname").isString)
            .andExpect(jsonPath("$.data.user.profileImage").isString)
            .andExpect(jsonPath("$.data.user.identityType").value("GUEST"))
    }

    @Test
    fun `POST auth token refresh - 유효한 refreshToken 으로 새 토큰 쌍이 발급된다`() {
        val guestResult =
            mockMvc()
                .perform(post("/api/v1/auth/guest").contentType(MediaType.APPLICATION_JSON).header("X-Client-Type", "app"))
                .andReturn()
        val refreshToken =
            objectMapper
                .readTree(guestResult.response.contentAsString)
                .at("/data/refreshToken")
                .asText()

        val body = objectMapper.writeValueAsString(mapOf("refreshToken" to refreshToken))
        mockMvc()
            .perform(
                post("/api/v1/auth/token/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Client-Type", "app")
                    .content(body),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.accessToken").isString)
            .andExpect(jsonPath("$.data.refreshToken").isString)
    }

    @Test
    fun `POST auth token refresh - 위조된 refreshToken 으로 401 이 반환된다`() {
        val body = objectMapper.writeValueAsString(mapOf("refreshToken" to "invalid.token.value"))
        mockMvc()
            .perform(
                post("/api/v1/auth/token/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isUnauthorized)
            // 위조 토큰은 AuthException.invalidToken → AUTH-001 (auth 도메인 code 이관 완료, 더는 공통 폴백 아님).
            .andExpect(jsonPath("$.code").value("AUTH-001"))
    }

    @Test
    fun `POST auth token refresh - refreshToken 이 빈 문자열이면 400 이 반환된다`() {
        val body = objectMapper.writeValueAsString(mapOf("refreshToken" to ""))
        mockMvc()
            .perform(
                post("/api/v1/auth/token/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isBadRequest)
            // 공통 400(입력 검증)은 COMMON-INVALID-INPUT 을 싣는다(회귀 가드).
            .andExpect(jsonPath("$.code").value("COMMON-INVALID-INPUT"))
    }

    @Test
    fun `POST auth logout - 인증된 사용자가 로그아웃하면 200 이 반환된다`() {
        val guestResult =
            mockMvc()
                .perform(post("/api/v1/auth/guest").contentType(MediaType.APPLICATION_JSON).header("X-Client-Type", "app"))
                .andReturn()
        val accessToken =
            objectMapper
                .readTree(guestResult.response.contentAsString)
                .at("/data/accessToken")
                .asText()

        mockMvc()
            .perform(
                post("/api/v1/auth/logout")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken"),
            ).andExpect(status().isOk)
    }

    @Test
    fun `POST auth logout - Authorization 헤더 없으면 401 이 반환된다`() {
        mockMvc()
            .perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `POST auth guest - 연속 두 번 호출하면 서로 다른 닉네임이 발급된다`() {
        val firstResult =
            mockMvc()
                .perform(post("/api/v1/auth/guest").contentType(MediaType.APPLICATION_JSON).header("X-Client-Type", "app"))
                .andReturn()
        val secondResult =
            mockMvc()
                .perform(post("/api/v1/auth/guest").contentType(MediaType.APPLICATION_JSON).header("X-Client-Type", "app"))
                .andReturn()

        val firstNickname = objectMapper.readTree(firstResult.response.contentAsString).at("/data/user/nickname").asText()
        val secondNickname = objectMapper.readTree(secondResult.response.contentAsString).at("/data/user/nickname").asText()
        assertNotEquals(firstNickname, secondNickname)
    }
}
