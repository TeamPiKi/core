package com.depromeet.piki.admin.template

import com.depromeet.piki.support.IntegrationTestSupport
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext

// 알림 템플릿 목록(#250)이 종류 배지를 kind 기준(위시/토너먼트)으로 렌더하는지 검증한다(#473 고도화 — 옛 활동/시스템 카테고리 폐기).
// Thymeleaf 표현식 에러는 부팅이 아니라 '렌더 시점'에 드러나므로, 화면 이관은 실제 GET 렌더로만 회귀를 잡을 수 있다.
// 템플릿 행은 Flyway 시드로 이미 존재하고, admin 게이트는 IntegrationTestSupport 의 admin.local-bypass 로 우회된다.
@Transactional
class AdminTemplatePageRenderIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var webApplicationContext: WebApplicationContext

    private fun mockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    @Test
    fun `템플릿 목록은 종류 배지를 위시·토너먼트 라벨로 렌더한다`() {
        mockMvc()
            .perform(get("/admin/templates"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("<th>종류</th>")))
            // 파싱 알림(위시 기본값) · 토너먼트 알림이 각각 자기 배지 클래스·라벨로 나온다.
            .andExpect(content().string(containsString("class=\"badge WISH\"")))
            .andExpect(content().string(containsString("위시")))
            .andExpect(content().string(containsString("class=\"badge TOURNAMENT\"")))
            .andExpect(content().string(containsString("토너먼트")))
            // 옛 카테고리 축(활동/시스템)의 잔재가 목록에 남아 있지 않다.
            .andExpect(content().string(not(containsString("badge ACTIVITY"))))
    }

    @Test
    fun `템플릿 편집 화면은 시드된 문구를 채워 렌더된다`() {
        mockMvc()
            .perform(get("/admin/templates/TOURNAMENT_JOINED"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("TOURNAMENT_JOINED")))
    }
}
