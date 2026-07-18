package com.depromeet.piki.auth.config

import com.depromeet.piki.common.web.TraceIdHeaderFilter
import com.depromeet.piki.support.IntegrationTestSupport
import io.micrometer.tracing.Tracer
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

// Spring Security 필터 체인은 DispatcherServlet 이전이라 GlobalExceptionHandler 가 잡지 못하고,
// 미인증 401 응답은 EntryPoint 에서만 작성된다. 본문이 빈 채로 내려가던 회귀(#213)가 다시 새지 않도록,
// 필터 단 401 응답이 다른 엔드포인트와 같은 ApiResponseBody contract (detail 등) 로 내려가는지,
// 그리고 그 응답에도 X-Trace-Id 헤더가 실리는지 한 자리에서 검증한다.
//
// 권한 부족 403 은 더 이상 필터 단(AccessDeniedHandler)에서 나지 않는다 — 인가가 전부 도메인 레이어로
// 내려가(authority 게이트 제거), 403 은 GlobalExceptionHandler 가 만든다. AccessDeniedHandler 는 방어용으로
// wiring 만 남아 트리거가 없어, 여기선 401 경로만 검증한다.
//
// X-Trace-Id 는 TraceIdHeaderFilter 가 Security 필터 바깥에서 박으므로 401 응답에도 실린다(운영 order).
// MockMvc 는 관측 필터를 자동 포함하지 않으니, 그 필터를 체인에 직접 얹고 Tracer 로 trace 스코프를 연 채 호출해
// 운영 흐름을 재현한다.
class SecurityErrorResponseIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var tracer: Tracer

    @Test
    fun `토큰 없이 보호 엔드포인트 호출 시 401 응답이 ApiResponseBody contract 로 내려간다`() {
        val span = tracer.nextSpan().name("security-error").start()
        val expectedTraceId = span.context().traceId()
        tracer.withSpan(span).use {
            buildMockMvc()
                .perform(post("/api/v1/wishlists"))
                .andExpect(status().isUnauthorized)
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                // 필터 단 401 도 code 기반 contract 로 내려간다 — COMMON-UNAUTHORIZED 고정(회귀 가드).
                .andExpect(jsonPath("$.code", equalTo("COMMON-UNAUTHORIZED")))
                .andExpect(jsonPath("$.detail", notNullValue()))
                .andExpect(header().string(TraceIdHeaderFilter.HEADER, expectedTraceId))
        }
        span.end()
    }

    @Test
    fun `위조된 Bearer 토큰으로 보호 엔드포인트 호출 시 401 응답이 ApiResponseBody contract 로 내려간다`() {
        val span = tracer.nextSpan().name("security-error").start()
        val expectedTraceId = span.context().traceId()
        tracer.withSpan(span).use {
            buildMockMvc()
                .perform(
                    post("/api/v1/wishlists")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid.token.value"),
                ).andExpect(status().isUnauthorized)
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code", equalTo("COMMON-UNAUTHORIZED")))
                .andExpect(jsonPath("$.detail", notNullValue()))
                .andExpect(header().string(TraceIdHeaderFilter.HEADER, expectedTraceId))
        }
        span.end()
    }

    private fun buildMockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .addFilters<DefaultMockMvcBuilder>(TraceIdHeaderFilter(tracer))
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()
}
