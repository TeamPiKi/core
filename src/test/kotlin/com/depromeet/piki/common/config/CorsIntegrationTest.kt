package com.depromeet.piki.common.config

import com.depromeet.piki.support.IntegrationTestSupport
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

class CorsIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Test
    fun `인증 경로의 preflight OPTIONS 는 허용 origin 에 대해 인증 없이 200 으로 통과한다`() {
        // SecurityConfig 에 http.cors() 가 없으면 preflight 가 anyRequest().authenticated() 에 잡혀
        // 401 이 되어 브라우저의 인증 요청(member 생성 등)이 전부 막힌다. 회귀 가드.
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()

        mockMvc
            .perform(
                options("/api/v1/dev/users")
                    .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"),
            ).andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"))
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
    }

    @Test
    fun `127_0_0_1 origin 의 preflight 도 허용된다`() {
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()

        mockMvc
            .perform(
                options("/api/v1/dev/users")
                    .header(HttpHeaders.ORIGIN, "http://127.0.0.1:3000")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"),
            ).andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://127.0.0.1:3000"))
    }

    @Test
    fun `retire 된 옛 depromeet origin 의 preflight 는 거부된다`() {
        // #488 retire — 옛 dev.depromeet18team3.cloud 도메인 origin 은 화이트리스트에서 제거됐다.
        // dev 프론트의 정상 경로는 dev.piki.day 로 옮겨갔고(아래 테스트), 옛 origin 은 이제 차단된다. 회귀 가드.
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()

        mockMvc
            .perform(
                options("/api/v1/dev/users")
                    .header(HttpHeaders.ORIGIN, "https://dev.depromeet18team3.cloud")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `piki_day 프론트 origin 의 preflight 도 허용된다`() {
        // 도메인 이관(#488) — piki.day 프론트가 API 를 호출한다. 옛 도메인과 듀얼런으로 함께 허용한다.
        // 화이트리스트 누락 시 piki.day 에서 모든 인증/JSON 요청이 CORS 로 막힌다. 회귀 가드.
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()

        mockMvc
            .perform(
                options("/api/v1/dev/users")
                    .header(HttpHeaders.ORIGIN, "https://dev.piki.day")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"),
            ).andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://dev.piki.day"))
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
    }

    @Test
    fun `dev(비-prod)에서는 LAN 사설망 IP origin 의 preflight 도 허용된다`() {
        // 실기기 dev 테스트(#509) — 폰 브라우저가 LAN IP(192.168.x.y:port)로 프론트에 접속하면 Origin 이 그
        // 사설망 IP 로 박힌다. 비-prod 프로파일에서 allowedOriginPatterns 로 허용함을 검증한다(통합 컨텍스트는 prod 아님).
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()

        mockMvc
            .perform(
                options("/api/v1/dev/users")
                    .header(HttpHeaders.ORIGIN, "http://192.168.0.42:5173")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"),
            ).andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://192.168.0.42:5173"))
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
    }

    @Test
    fun `화이트리스트에 없는 origin 의 preflight 는 403 으로 거부된다`() {
        // 허용 목록 검증이 실제로 동작함을 함께 단언해 테스트가 vacuous 하지 않도록 한다.
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()

        mockMvc
            .perform(
                options("/api/v1/dev/users")
                    .header(HttpHeaders.ORIGIN, "https://evil.example.com")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"),
            ).andExpect(status().isForbidden)
    }
}
