package com.depromeet.piki.common.exception

import com.depromeet.piki.support.IntegrationTestSupport
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper

// Spring 표준 MVC 예외가 catch-all 에 먹혀 전부 500 으로 새던 갭(#300)의 회귀 가드.
// 각 케이스가 (1) 올바른 4xx status (2) ApiResponseBody contract(status·code·detail) 를 단언한다.
@Transactional
class GlobalExceptionHandlerIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private fun mockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    @Test
    fun `깨진 JSON body 는 500 이 아니라 400 으로 매핑된다`() {
        mockMvc()
            .perform(
                post("/api/v1/auth/login/google").contentType(MediaType.APPLICATION_JSON).content("{ broken json "),
            ).andExpect(status().isBadRequest)
            // handleExceptionInternal 이 실제 응답 body 에 공통 code 를 싣는지까지 계약으로 고정한다(내부 매핑만이 아니라).
            .andExpect(jsonPath("$.code").value("COMMON-INVALID-INPUT"))
            .andExpect(jsonPath("$.detail").isString)
    }

    @Test
    fun `지원하지 않는 Content-Type 은 500 이 아니라 415 로 매핑된다`() {
        mockMvc()
            .perform(
                post("/api/v1/auth/login/google").contentType(MediaType.TEXT_PLAIN).content("hello"),
            ).andExpect(status().isUnsupportedMediaType)
            .andExpect(jsonPath("$.code").value("COMMON-UNSUPPORTED-MEDIA-TYPE"))
    }

    @Test
    fun `refresh 의 malformed body 는 500 이 아니라 400 으로 매핑된다`() {
        mockMvc()
            .perform(
                post("/api/v1/auth/token/refresh").contentType(MediaType.APPLICATION_JSON).content("{ bad "),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `경로변수 타입 불일치(UUID 자리에 문자열)는 500 이 아니라 400 으로 매핑된다`() {
        // /api/v1/dev/{userId}/token 은 GUEST 권한이 필요하므로 게스트 토큰으로 통과시킨 뒤,
        // userId 자리에 UUID 가 아닌 문자열을 넣어 MethodArgumentTypeMismatchException 을 유발한다.
        val guestResponse =
            mockMvc()
                .perform(post("/api/v1/auth/guest").header("X-Client-Type", "app"))
                .andReturn()
                .response.contentAsString
        val guestToken = objectMapper.readTree(guestResponse).at("/data/accessToken").asString()

        mockMvc()
            .perform(
                post("/api/v1/dev/not-a-uuid/token").header(HttpHeaders.AUTHORIZATION, "Bearer $guestToken"),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `검증 실패(@Valid 위반)는 400 으로 매핑되고 detail 에 위반 필드 메시지를 노출한다`() {
        // refresh 는 @Valid + TokenRefreshRequest(@NotBlank) 라, refreshToken 이 빈 문자열이면
        // MethodArgumentNotValidException → detailOf 가 위반 필드의 메시지를 노출하는 분기를 탄다(필드명 접두사 없음).
        mockMvc()
            .perform(
                post("/api/v1/auth/token/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"refreshToken":""}"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value("다시 로그인해 주세요."))
    }

    @Test
    fun `POST 전용 엔드포인트에 GET 하면 500 이 아니라 405 로 매핑된다`() {
        // /api/v1/dev/{userId}/token 은 POST 전용 + GUEST 권한 필요. 게스트 토큰으로 보안을 통과시킨 뒤
        // GET 으로 호출해 HttpRequestMethodNotSupportedException(405) 을 유발한다.
        val guest =
            objectMapper.readTree(
                mockMvc()
                    .perform(post("/api/v1/auth/guest").header("X-Client-Type", "app"))
                    .andReturn()
                    .response.contentAsString,
            )
        val guestId = guest.at("/data/user/id").asString()
        val guestToken = guest.at("/data/accessToken").asString()

        mockMvc()
            .perform(get("/api/v1/dev/$guestId/token").header(HttpHeaders.AUTHORIZATION, "Bearer $guestToken"))
            .andExpect(status().isMethodNotAllowed)
            .andExpect(jsonPath("$.code").value("COMMON-METHOD-NOT-ALLOWED"))
    }
}
