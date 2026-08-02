package com.depromeet.piki.admin.config

import com.depromeet.piki.support.IntegrationTestSupport
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

// 공통 상단바(admin/fragments :: topbar)의 API 레퍼런스 문서 바로가기(#867)를 검증한다.
// 링크는 AdminHeaderInterceptor 가 주입하는 adminDocsEnabled(docs.enabled) 로 게이팅되며, 테스트 컨텍스트는
// docs.enabled=true(test application.yml)라 '노출되는 쪽'을 검증한다. 반대(prod 에서 숨김)는 프로퍼티를 바꿔야
// 하는데 @TestPropertySource 가 컨텍스트 캐시 규약상 금지라 통합으로 만들지 않는다 — 게이팅 자체는 th:if 한 줄이고,
// 링크가 가리키는 /docs 가 같은 플래그(WebConfig)로 사라지는 것은 아래 두 번째 테스트가 같은 컨텍스트에서 확인한다.
class AdminTopbarDocsLinkIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    private fun mockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    @Test
    fun `문서가 서빙되는 환경의 백오피스 상단바는 API 문서 바로가기를 새 탭 링크로 렌더한다`() {
        mockMvc()
            .perform(get("/admin"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("class=\"pkdocs\"")))
            .andExpect(content().string(containsString("href=\"/docs/index.html\"")))
            // 백오피스 세션을 잃지 않도록 새 탭으로 연다(rel 은 opener 탈취·referrer 유출 차단).
            .andExpect(content().string(containsString("target=\"_blank\"")))
            .andExpect(content().string(containsString("rel=\"noopener noreferrer\"")))
    }

    @Test
    fun `상단바가 가리키는 문서 경로는 실제로 서빙된다 - 죽은 링크 회귀 가드`() {
        // 링크 문자열만 단언하면 리소스 핸들러 경로(WebConfig)가 바뀌어도 테스트가 초록불이라, 대상까지 실제로 친다.
        mockMvc()
            .perform(get("/docs/index.html"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("PIKI API Docs")))
    }
}
