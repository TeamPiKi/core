package com.depromeet.piki.common.controller

import com.depromeet.piki.support.IntegrationTestSupport
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

class DocsAccessIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Test
    fun `GET docs index html - 인증 없이 200 응답이 와야 한다 (Stoplight UI 접근 회귀 가드)`() {
        // anyRequest().authenticated() 가 적용된 상태에서 /docs/** 가 permitAll 명시 누락 시
        // 401 응답으로 운영 docs UI (https://api.depromeet18team3.cloud/docs/index.html) 가 차단된다.
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()

        mockMvc
            .perform(get("/docs/index.html"))
            .andExpect(status().isOk)
    }

    @Test
    fun `GET favicon ico - 인증 없이 200 응답이 와야 한다 (브라우저 자동 요청 401 회귀 가드)`() {
        // /favicon.ico 는 브라우저가 모든 페이지에서 자동 요청하는 사이트 전역 자산이다.
        // permitAll 명시가 빠지면 anyRequest().authenticated() 에 잡혀 401 이 되어 docs
        // 어느 탭에서도 favicon 이 안 뜬다. 회귀 가드.
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()

        mockMvc
            .perform(get("/favicon.ico"))
            .andExpect(status().isOk)
    }

    @Test
    fun `GET v3 api-docs - 인증 없이 200 응답이 와야 한다 (Stoplight 가 fetch 하는 OpenAPI spec)`() {
        // Stoplight UI 가 동작하려면 spec endpoint 도 인증 없이 접근 가능해야 한다.
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()

        mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
    }

    @Test
    fun `GET v3 api-docs - JWT Bearer SecurityScheme 가 스펙에 선언되어 있다`() {
        // 이 선언이 없으면 문서 UI 가 "인증 필요" 를 알 수 없어 토큰 입력란·Authorization 헤더 cURL
        // 샘플을 그리지 못한다 (Security 필터 규칙은 springdoc 이 모름). 회귀 가드.
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()

        mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
            .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
            .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"))
            // 최상위 글로벌 security 로 모든 엔드포인트에 기본 Bearer 요구가 걸린다.
            .andExpect(jsonPath("$.security[0].bearerAuth").exists())
    }

    @Test
    fun `GET v3 api-docs - public 은 인증 해제되고 보호 엔드포인트는 글로벌 Bearer 를 상속한다`() {
        // 글로벌 Bearer 요구가 게스트 생성 같은 진입점까지 자물쇠로 덮으면 문서가 거짓이 된다.
        // @SecurityRequirements(빈) 으로 public operation 의 security 가 빈 배열이 되는지,
        // 인증 필요한 dev/users 는 operation 에 security 명시 없이 글로벌을 상속하는지 회귀 가드.
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()

        mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            // public: @SecurityRequirements(빈) → operation security 가 빈 배열로 글로벌 요구를 끊는다.
            .andExpect(jsonPath("$.paths['/api/v1/auth/guest'].post.security").isArray)
            .andExpect(jsonPath("$.paths['/api/v1/auth/guest'].post.security").isEmpty)
            // 보호: operation 에 security 명시 없음 → 최상위 글로벌 Bearer 상속 (문서 UI 에 자물쇠 표시).
            .andExpect(jsonPath("$.paths['/api/v1/dev/users'].post.security").doesNotExist())
    }

    @Test
    fun `GET v3 api-docs - OAuth 로그인 선언 실패 응답에 example payload 가 부착된다 (회귀 가드)`() {
        // OperationExamples.add 는 @ApiResponse 가 선언된 status 에만 example 을 붙인다(없으면 조용히 무시).
        // #313 에서 OAuth 로그인 실패 응답을 400/401/502 로 정밀화하며 example 도 함께 붙였고, #762 로 misconfigured 를
        // 502→500 으로 교정하며 SERVER_ERROR example 이 500 으로 이동했다 — 선언↔example 짝이 유지되는지 회귀 가드.
        // provider 장애(502 RETRYABLE)와 우리 설정 오류(500 SERVER_ERROR)가 각각 자기 status 에 붙어야 분리가 잠긴다.
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()

        val login502 = "$.paths['/api/v1/auth/login/{provider}'].post.responses['502'].content['application/json'].examples"
        val login500 = "$.paths['/api/v1/auth/login/{provider}'].post.responses['500'].content['application/json'].examples"
        mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(
                jsonPath("$.paths['/api/v1/auth/login/{provider}'].post.responses['400'].content['application/json'].examples").exists(),
            ).andExpect(
                jsonPath("$.paths['/api/v1/auth/login/{provider}'].post.responses['401'].content['application/json'].examples").exists(),
            ).andExpect(jsonPath("$login502['소셜 제공자 장애 (RETRYABLE — 재시도 가능)']").exists())
            .andExpect(jsonPath("$login500['우리 OAuth 설정/요청 오류 (SERVER_ERROR — 재시도 무의미)']").exists())
            // 이동이 아니라 복제였으면(502 에 misconfigured example 잔존) 위 exists 단언만으로는 통과해버린다.
            // 부재까지 함께 잠가야 "provider 장애 502 / 우리 설정 오류 500" 분리가 회귀 가드로 성립한다.
            .andExpect(jsonPath("$login502['우리 OAuth 설정/요청 오류 (SERVER_ERROR — 재시도 무의미)']").doesNotExist())
    }

    @Test
    fun `GET v3 api-docs - 대표 실패 응답에 example payload 가 부착된다 (회귀 가드)`() {
        // OperationExamples.add 는 @ApiResponse 가 선언된 status 에만 example 을 붙인다(없으면 조용히 무시).
        // 과거 401·일부 4xx 응답에 example 이 통째로 누락돼 있었다. 선언과 example 이 짝을 이루는지 회귀 가드.
        // 전 엔드포인트 전수가 아니라, 광범위 누락이던 대표 케이스(401·409·404)만 spot 검증한다.
        val mockMvc =
            MockMvcBuilders
                .webAppContextSetup(webApplicationContext)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()

        mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            // wishlist: 모든 메서드에 누락돼 있던 401 (Security MEMBER 요구) example 부착.
            .andExpect(
                jsonPath(
                    "$.paths['/api/v1/wishlists'].post.responses['401'].content['application/json'].examples",
                ).exists(),
            )
            // tournament 시작: 실패 example 이 전무였던 409 (상태 충돌) example 부착.
            .andExpect(
                jsonPath(
                    "$.paths['/api/v1/tournaments/{tournamentId}/start'].post.responses['409'].content['application/json'].examples",
                ).exists(),
            )
            // dev 단건 유저: 신규 DevUserApiExamples 빈의 404 example 부착.
            .andExpect(
                jsonPath(
                    "$.paths['/api/v1/dev/users/{userId}'].get.responses['404'].content['application/json'].examples",
                ).exists(),
            )
    }
}
