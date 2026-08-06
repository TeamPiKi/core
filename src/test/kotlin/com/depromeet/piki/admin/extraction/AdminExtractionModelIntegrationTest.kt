package com.depromeet.piki.admin.extraction

import com.depromeet.piki.product.service.remote.DbExtractionModelSettings
import com.depromeet.piki.product.service.remote.ExtractionModelJpaRepository
import com.depromeet.piki.product.service.remote.ExtractionTarget
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubExtractionModelProbe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 백오피스 추출 모델 화면의 저장 게이트 contract (#875) — "프로브가 성공한 모델만 등록된다"가 이 기능의 전부라,
// 게이트를 통과하는 경로와 막히는 경로를 함께 고정한다.
//
// @Transactional 자동 롤백을 쓰지 않는다 — 저장·해제가 afterCommit 에 캐시 reload 를 걸고(롤백되면 타지 않는다),
// 모델 캐시(@Volatile)는 롤백으로 되돌아가지 않아 다른 테스트로 누수된다. 각 테스트가 자기 행을 정리하고 reload 한다.
// target 은 LINK · IMAGE 둘뿐이라 UUID 같은 격리 식별자를 쓸 수 없으므로, 테스트마다 다른 축을 골라 간섭을 줄인다.
class AdminExtractionModelIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var repository: ExtractionModelJpaRepository

    @Autowired
    private lateinit var settings: DbExtractionModelSettings

    @Autowired
    private lateinit var probe: StubExtractionModelProbe

    private fun mockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    private fun cleanUp(target: ExtractionTarget) {
        repository.deleteById(target.name)
        settings.reload()
    }

    @Test
    fun `프로브가 통과한 모델만 저장되고 캐시에 즉시 반영된다`() {
        val mockMvc = mockMvc()
        probe.behavior = { _, _ -> }
        probe.calls.clear()
        try {
            mockMvc
                .perform(post("/admin/extraction-models/LINK").with(csrf()).param("model", "gemini-probe-ok"))
                .andExpect(status().is3xxRedirection)
                .andExpect(redirectedUrl("/admin/extraction-models?updated"))

            assertEquals("gemini-probe-ok", settings.modelOf(ExtractionTarget.LINK))
            assertEquals("gemini-probe-ok", repository.findById("LINK").get().model)
            // 저장 전에 프로브를 거쳤다는 사실 자체가 게이트의 본질이다.
            assertEquals(listOf(ExtractionTarget.LINK to "gemini-probe-ok"), probe.calls)
        } finally {
            cleanUp(ExtractionTarget.LINK)
        }
    }

    @Test
    fun `프로브가 거절하면 저장되지 않고 목록 화면으로 되돌아온다`() {
        val mockMvc = mockMvc()
        probe.behavior = { _, _ -> throw IllegalArgumentException("probe rejected") }
        try {
            // 거절은 redirect 가 아니라 목록 재렌더다 — 제출값을 유지한 채 사유를 보여줘야 하기 때문.
            mockMvc
                .perform(post("/admin/extraction-models/IMAGE").with(csrf()).param("model", "gemini-nonexistent"))
                .andExpect(status().isOk)

            assertTrue(repository.findById("IMAGE").isEmpty, "거절된 모델이 저장되면 게이트가 뚫린 것이다")
            assertNull(settings.modelOf(ExtractionTarget.IMAGE))
        } finally {
            cleanUp(ExtractionTarget.IMAGE)
        }
    }

    // 프로브까지 갈 가치가 없는 입력은 경계에서 걸러 외부 호출을 낭비하지 않는다.
    @Test
    fun `모델명에 경로를 통째로 붙여넣으면 프로브를 부르지 않고 거절한다`() {
        val mockMvc = mockMvc()
        probe.behavior = { _, _ -> error("경계 검증에서 걸러야 할 입력이 프로브까지 갔다.") }
        probe.calls.clear()
        try {
            mockMvc
                .perform(post("/admin/extraction-models/LINK").with(csrf()).param("model", "models/gemini-3-flash"))
                .andExpect(status().isOk)

            assertTrue(repository.findById("LINK").isEmpty)
            assertTrue(probe.calls.isEmpty(), "경계에서 걸린 입력은 외부 호출을 유발하면 안 된다")
        } finally {
            cleanUp(ExtractionTarget.LINK)
        }
    }

    @Test
    fun `해제하면 행이 사라져 extractor 기본 모델로 돌아간다`() {
        val mockMvc = mockMvc()
        probe.behavior = { _, _ -> }
        try {
            mockMvc
                .perform(post("/admin/extraction-models/IMAGE").with(csrf()).param("model", "gemini-to-be-cleared"))
                .andExpect(status().is3xxRedirection)
            assertEquals("gemini-to-be-cleared", settings.modelOf(ExtractionTarget.IMAGE))

            mockMvc
                .perform(post("/admin/extraction-models/IMAGE/clear").with(csrf()))
                .andExpect(status().is3xxRedirection)
                .andExpect(redirectedUrl("/admin/extraction-models?cleared"))

            assertTrue(repository.findById("IMAGE").isEmpty)
            assertNull(settings.modelOf(ExtractionTarget.IMAGE), "해제 후엔 요청에 모델이 실리지 않아야 한다")
        } finally {
            cleanUp(ExtractionTarget.IMAGE)
        }
    }
}
