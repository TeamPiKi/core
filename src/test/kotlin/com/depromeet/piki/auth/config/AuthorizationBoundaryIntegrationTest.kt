package com.depromeet.piki.auth.config

import com.depromeet.piki.support.IntegrationTestSupport
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

class AuthorizationBoundaryIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    // SecurityConfig 의 인증 경계는 컨트롤러 로직이 아니라 필터 단 정책이라, 보호 엔드포인트 전체를
    // 한 곳에서 망라해 "인증 요구가 silent 하게 풀리는" 회귀(예: permitAll 오추가, 글로벌 요구 제거)를 잡는다.
    // OpenAPI 자물쇠 표시는 문서일 뿐 실제 경계가 아니므로, 진짜 경계인 여기서 401 을 직접 단언한다.
    // 일부는 각 컨트롤러 통합 테스트의 미인증 401 시나리오와 겹치나, 여기서는 정책 전체를 한눈에 회귀 검증한다.
    @ParameterizedTest(name = "[{index}] {0} {1} - 토큰 없으면 401")
    @MethodSource("protectedEndpoints")
    fun `보호 엔드포인트는 Authorization 헤더 없이 호출하면 401 을 반환한다`(
        method: HttpMethod,
        path: String,
    ) {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()

        mockMvc
            .perform(request(method, path))
            .andExpect(status().isUnauthorized)
    }

    // 루트(/)는 우리 API 표면이 아닌데 인증 벽에 걸려 401 + 인증실패 로그(노이즈)를 남기던 회귀를 막는다.
    // WebConfig 가 /docs 로 리다이렉트한다. (/favicon.ico 의 공개 서빙은 #373 의 DocsAccessIntegrationTest 가 검증)
    @Test
    fun `루트는 인증 없이 API 문서로 리다이렉트된다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()

        mockMvc
            .perform(request(HttpMethod.GET, "/"))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/docs/index.html"))
    }

    companion object {
        // SecurityConfig 에서 permitAll 이 아닌(= 인증 필요한) 모든 엔드포인트.
        // public 진입점(POST /auth/guest·/auth/token/refresh, GET /health)은 제외 — 인증 없이 통과해야 하며
        // 그 정상 동작은 각 컨트롤러 통합 테스트·CorsIntegrationTest 가 검증한다.
        @JvmStatic
        fun protectedEndpoints(): List<Arguments> =
            listOf(
                arguments(HttpMethod.POST, "/api/v1/dev/users"),
                arguments(HttpMethod.POST, "/api/v1/dev/00000000-0000-0000-0000-000000000000/token"),
                arguments(HttpMethod.POST, "/api/v1/auth/logout"),
                arguments(HttpMethod.POST, "/api/v1/wishlists"),
                arguments(HttpMethod.POST, "/api/v1/wishlists/images/presigned"),
                arguments(HttpMethod.POST, "/api/v1/wishlists/images/confirm"),
                arguments(HttpMethod.GET, "/api/v1/wishlists"),
                arguments(HttpMethod.PATCH, "/api/v1/wishlists/1"),
                arguments(HttpMethod.DELETE, "/api/v1/wishlists/1"),
                arguments(HttpMethod.POST, "/api/v1/tournaments"),
                arguments(HttpMethod.POST, "/api/v1/tournaments/1/items"),
                arguments(HttpMethod.DELETE, "/api/v1/tournaments/1/items/1"),
                arguments(HttpMethod.POST, "/api/v1/tournaments/1/start"),
                arguments(HttpMethod.POST, "/api/v1/tournaments/1/matches"),
                arguments(HttpMethod.GET, "/api/v1/tournaments"),
                arguments(HttpMethod.GET, "/api/v1/tournaments/1"),
                arguments(HttpMethod.GET, "/api/v1/users/me"),
                arguments(HttpMethod.PATCH, "/api/v1/users/me"),
                // nickname/check 는 비회원 게스트 참여 화면에서 JWT 없이 호출하므로 permitAll — 이 목록에서 제외
            )
    }
}
