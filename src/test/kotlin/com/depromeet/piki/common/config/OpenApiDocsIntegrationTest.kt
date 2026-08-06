package com.depromeet.piki.common.config

import com.depromeet.piki.support.IntegrationTestSupport
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenApiDocsIntegrationTest : IntegrationTestSupport() {
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
    fun `api-docs 의 태그 그룹은 도메인 흐름 순서대로 노출된다`() {
        // 문서 UI(Stoplight Elements)의 그룹 순서는 spec 의 tags[] 배열 순서를 따른다. springdoc 기본은
        // 핸들러 스캔 순서(비결정적)라, OpenApiConfig.sortTagsByDomainFlow() 가 이를 도메인 흐름 순으로
        // 재정렬한다. health-controller 는 @Tag 미선언 자동 태그라 top-level tags[] 에 없고(Elements 가
        // 사이드바 맨 뒤에 자동 배치) 여기 단언 대상이 아니다. 이 순서를 회귀 가드로 고정한다(새 태그가
        // 늘면 단언이 깨져 TAG_ORDER 갱신을 강제).
        val expected =
            listOf(
                "Auth",
                "User",
                "Wishlist",
                "Tournament",
                "Tournament Item",
                "Notification",
                "Announcement",
                "FCM",
                "Dev",
            )

        val response =
            mockMvc()
                .perform(get("/v3/api-docs"))
                .andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString

        val tags = objectMapper.readTree(response).get("tags")
        val actual = (0 until tags.size()).map { i -> tags.get(i).get("name").asText() }
        assertEquals(expected, actual)

        // 각 그룹에 설명이 채워져 있어야 한다(빈 description 회귀 방지).
        (0 until tags.size()).forEach { i ->
            val tag = tags.get(i)
            assertTrue(
                tag.get("description").asText().isNotBlank(),
                "${tag.get("name").asText()} 태그에 description 이 비어 있다",
            )
        }
    }

    @Test
    fun `admin·내부 컨트롤러는 api-docs 에 노출되지 않는다`() {
        // admin Thymeleaf 페이지(/admin/**)·슬랙 웹훅(/admin-access/**)은 유저 API 가 아니라
        // 각 컨트롤러의 @Hidden 으로 spec 에서 제외한다. paths 에 /admin* 가 없어야 한다.
        val response =
            mockMvc()
                .perform(get("/v3/api-docs"))
                .andExpect(status().isOk)
                .andReturn()
                .response
                .contentAsString

        val pathsJson = objectMapper.readTree(response).get("paths").toString()
        assertFalse(pathsJson.contains("/admin"), "admin 경로가 api-docs paths 에 노출됨")
    }
}
