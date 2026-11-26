package com.depromeet.piki.admin.extraction

import com.depromeet.piki.admin.audit.AdminAuditAction
import com.depromeet.piki.admin.audit.AdminAuditLogRepository
import com.depromeet.piki.contracts.extraction.v1.ExtractionRule
import com.depromeet.piki.product.rule.ExtractionRuleJpaRepository
import com.depromeet.piki.product.rule.ExtractionRulePublisher
import com.depromeet.piki.product.rule.ExtractionRuleRevisionJpaRepository
import com.depromeet.piki.product.service.remote.ExtractionRuleProbeResult
import com.depromeet.piki.product.service.remote.ExtractionRuleReading
import com.depromeet.piki.support.IntegrationTestSupport
import com.depromeet.piki.support.StubExtractionRuleProbe
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// 백오피스 몰별 추출 규칙 화면의 저장 게이트 contract (#1318). 켠 채 저장하는 규칙은 (1) 시험 추출이 "적용된다" 고
// 답하고 (2) 운영자가 그 본문의 시험 결과를 이미 본 경우에만 저장된다. 끄는 저장은 언제나 된다.
//
// @Transactional 자동 롤백을 쓰지 않는다 — 저장이 끝난 뒤 배포본이 Redis 에 올라가고 Redis 는 롤백되지 않는다.
// 배포본은 테이블 전체로 만들어지므로 각 테스트가 끝에서 두 테이블과 키를 비운다(finally).
class AdminExtractionRuleIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var service: AdminExtractionRuleService

    @Autowired
    private lateinit var ruleRepository: ExtractionRuleJpaRepository

    @Autowired
    private lateinit var revisionRepository: ExtractionRuleRevisionJpaRepository

    @Autowired
    private lateinit var auditLogRepository: AdminAuditLogRepository

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    private lateinit var probe: StubExtractionRuleProbe

    private val sampleUrl = "https://www.oliveyoung.co.kr/store/goods/getGoodsDetail.do?goodsNo=A000000171788"
    private val ruleJson =
        """{"host": "oliveyoung.co.kr", "fetch": "RENDER", "currentPrice": {"css": {"selector": "[class^='GoodsDetailInfo_price__'] span"}}}"""

    private fun mockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    private fun probeResult(
        discarded: Boolean = false,
        unreadFields: List<String> = emptyList(),
        noDiscountApplied: Boolean = false,
        currentPrice: Int? = 39_700,
    ) = ExtractionRuleProbeResult(
        matched = true,
        byRule = ExtractionRuleReading(name = null, imageUrl = null, currentPrice = currentPrice, originalPrice = null),
        byCommon = ExtractionRuleReading(name = "공통 규칙이 읽은 이름", imageUrl = null, currentPrice = null, originalPrice = null),
        ruleError = null,
        finalUrl = sampleUrl,
        unreadFields = unreadFields,
        discarded = discarded,
        noDiscountApplied = noDiscountApplied,
    )

    private fun form(
        path: String,
        rule: String = ruleJson,
        label: String = "올리브영",
        enabled: Boolean = true,
    ): MockHttpServletRequestBuilder {
        val request = post(path).with(csrf()).param("label", label).param("ruleJson", rule).param("sampleUrl", sampleUrl)
        return if (enabled) request.param("enabled", "on") else request
    }

    // 운영자가 "시험 추출" 로 이 본문의 값을 봤을 때 폼에 실리는 확인 값
    private fun confirmationOf(rule: String = ruleJson): String =
        service.probe(ExtractionRuleDraft("올리브영", rule, sampleUrl, enabled = true)).confirmation

    private fun createConfirmed(mockMvc: MockMvc): Long {
        mockMvc.perform(form("/admin/extraction-rules").param("confirmation", confirmationOf())).andExpect(status().is3xxRedirection)
        return ruleRepository.findAll().single().getId()
    }

    private fun lastRevisionId(id: Long): String = service.find(id).lastRevisionId.toString()

    private fun cleanUp() {
        revisionRepository.deleteAll()
        ruleRepository.deleteAll()
        redisTemplate.delete(ExtractionRulePublisher.KEY)
    }

    @Test
    fun `읽은 값을 본 적 없는 본문은 저장하지 않고 시험 결과부터 보여 주며 그 확인을 실어 다시 누르면 저장된다`() {
        val mockMvc = mockMvc()
        probe.behavior = { _, _ -> probeResult() }
        try {
            mockMvc
                .perform(form("/admin/extraction-rules"))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("아직 저장하지 않았습니다")))
                .andExpect(content().string(containsString("39,700")))
                .andExpect(content().string(containsString("name=\"confirmation\" value=\"${confirmationOf()}\"")))
            assertEquals(0, ruleRepository.count())

            probe.calls.clear()
            mockMvc.perform(form("/admin/extraction-rules").param("confirmation", confirmationOf())).andExpect(status().is3xxRedirection)

            val saved = ruleRepository.findAll().single()
            assertEquals("올리브영", saved.label)
            assertTrue(saved.enabled)
            // 확인이 있어도 저장 직전에 그 주소와 그 규칙으로 한 번 더 시험한다
            val (probedLink, probedRule) = probe.calls.last()
            assertEquals(sampleUrl, probedLink.value.toString())
            assertEquals(ExtractionRule.FetchMode.RENDER, probedRule.fetch)
            assertEquals(true, redisTemplate.opsForValue().get(ExtractionRulePublisher.KEY)?.contains("\"id\":${saved.getId()}"))
            val audit = auditLogRepository.findAll().last { it.action == AdminAuditAction.EXTRACTION_RULE_UPDATE.name }
            assertEquals("#${saved.getId()} 올리브영 생성 (켬)", audit.detail)

            mockMvc
                .perform(get("/admin/extraction-rules/${saved.getId()}"))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("GoodsDetailInfo_price__")))
                .andExpect(content().string(containsString("변경 이력")))
            mockMvc
                .perform(get("/admin/extraction-rules"))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("oliveyoung.co.kr")))
        } finally {
            cleanUp()
        }
    }

    @Test
    fun `시험 결과를 본 뒤 본문을 고치면 이전 확인으로는 저장되지 않는다`() {
        val mockMvc = mockMvc()
        probe.behavior = { _, _ -> probeResult() }
        try {
            val seen = confirmationOf()
            val edited = ruleJson.replace("GoodsDetailInfo_price__", "GoodsDetailInfo_sale__")

            mockMvc
                .perform(form("/admin/extraction-rules", rule = edited).param("confirmation", seen))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("아직 저장하지 않았습니다")))

            assertEquals(0, ruleRepository.count())
        } finally {
            cleanUp()
        }
    }

    @Test
    fun `시험 추출에서 버려지는 규칙은 확인이 있어도 저장되지 않고 쓰던 내용과 사유가 화면에 남는다`() {
        val mockMvc = mockMvc()
        probe.behavior = { _, _ -> probeResult(discarded = true, unreadFields = listOf("currentPrice")) }
        try {
            mockMvc
                .perform(form("/admin/extraction-rules").param("confirmation", confirmationOf()))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("규칙이 버려집니다: currentPrice")))
                .andExpect(content().string(containsString("GoodsDetailInfo_price__")))

            assertEquals(0, ruleRepository.count())
            assertEquals(0, revisionRepository.count())
        } finally {
            cleanUp()
        }
    }

    @Test
    fun `끄는 저장은 시험 없이 되고 다시 켜는 저장은 시험을 통과해야 한다`() {
        val mockMvc = mockMvc()
        probe.behavior = { _, _ -> probeResult() }
        try {
            val id = createConfirmed(mockMvc)
            // 몰의 구조가 바뀌어 규칙이 더는 읽지 못하는 상황 — 이때 끌 수 있어야 한다
            probe.behavior = { _, _ -> probeResult(discarded = true, unreadFields = listOf("currentPrice")) }
            probe.calls.clear()

            mockMvc
                .perform(form("/admin/extraction-rules/$id", enabled = false).param("baseRevisionId", lastRevisionId(id)))
                .andExpect(redirectedUrl("/admin/extraction-rules/$id?saved"))

            assertFalse(ruleRepository.findById(id).get().enabled)
            assertTrue(probe.calls.isEmpty())
            assertEquals(false, redisTemplate.opsForValue().get(ExtractionRulePublisher.KEY)?.contains("\"id\":$id"))

            mockMvc
                .perform(
                    form("/admin/extraction-rules/$id", label = "올리브영 다시 켬")
                        .param("baseRevisionId", lastRevisionId(id))
                        .param("confirmation", confirmationOf()),
                ).andExpect(status().isOk)
                .andExpect(content().string(containsString("규칙이 버려집니다: currentPrice")))

            val unchanged = ruleRepository.findById(id).get()
            assertFalse(unchanged.enabled)
            assertEquals("올리브영", unchanged.label)
        } finally {
            cleanUp()
        }
    }

    @Test
    fun `화면을 연 뒤 다른 운영자가 고친 규칙은 덮어쓰지 않는다`() {
        val mockMvc = mockMvc()
        probe.behavior = { _, _ -> probeResult() }
        try {
            val id = createConfirmed(mockMvc)
            val openedAt = lastRevisionId(id)
            mockMvc
                .perform(form("/admin/extraction-rules/$id", enabled = false).param("baseRevisionId", openedAt))
                .andExpect(status().is3xxRedirection)

            mockMvc
                .perform(
                    form("/admin/extraction-rules/$id", label = "이름만 고침")
                        .param("baseRevisionId", openedAt)
                        .param("confirmation", confirmationOf()),
                ).andExpect(status().isOk)
                .andExpect(content().string(containsString("다른 운영자가 규칙을 고쳤습니다")))

            val kept = ruleRepository.findById(id).get()
            assertFalse(kept.enabled, "남이 방금 끈 규칙이 옛 폼의 체크로 다시 켜지면 안 된다")
            assertEquals("올리브영", kept.label)
        } finally {
            cleanUp()
        }
    }

    @Test
    fun `규칙을 지우면 배포본에서 빠지고 이력과 감사 로그에 남는다`() {
        val mockMvc = mockMvc()
        probe.behavior = { _, _ -> probeResult() }
        try {
            val id = createConfirmed(mockMvc)

            mockMvc
                .perform(post("/admin/extraction-rules/$id/delete").with(csrf()))
                .andExpect(redirectedUrl("/admin/extraction-rules?deleted"))

            assertFalse(ruleRepository.existsById(id))
            assertEquals(listOf("SAVED", "DELETED"), revisionRepository.findAll().sortedBy { it.getId() }.map { it.changeType })
            assertEquals(false, redisTemplate.opsForValue().get(ExtractionRulePublisher.KEY)?.contains("\"id\":$id"))
            val audit = auditLogRepository.findAll().last { it.action == AdminAuditAction.EXTRACTION_RULE_UPDATE.name }
            assertEquals("#$id 올리브영 삭제", audit.detail)
        } finally {
            cleanUp()
        }
    }

    @Test
    fun `시험 추출 버튼은 저장하지 않고 두 층이 읽은 값을 보여 주며 0 원도 읽은 값으로 표시한다`() {
        val mockMvc = mockMvc()
        probe.behavior = { _, _ -> probeResult(unreadFields = listOf("originalPrice"), noDiscountApplied = true, currentPrice = 0) }
        try {
            mockMvc
                .perform(form("/admin/extraction-rules/probe"))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("<td>0</td>")))
                .andExpect(content().string(containsString("공통 규칙이 읽은 이름")))
                .andExpect(content().string(containsString("할인 없음")))
                .andExpect(content().string(not(containsString("저장됐습니다"))))

            assertEquals(0, ruleRepository.count())
        } finally {
            cleanUp()
        }
    }

    @Test
    fun `읽지 못하는 입력은 extractor 를 부르기 전에 화면 에러로 돌아오고 없는 규칙은 목록으로 돌린다`() {
        val mockMvc = mockMvc()
        probe.calls.clear()
        probe.behavior = { _, _ -> probeResult() }
        try {
            mockMvc
                .perform(form("/admin/extraction-rules", rule = """{"host": "oliveyoung.co.kr", "curentPrice": {}}"""))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("규칙 JSON 을 읽지 못했습니다")))
            mockMvc
                .perform(form("/admin/extraction-rules", label = "가".repeat(101)))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("이름은 100자를 초과할 수 없습니다")))
            mockMvc
                .perform(post("/admin/extraction-rules/probe").with(csrf()).param("label", "올리브영").param("ruleJson", ruleJson).param("sampleUrl", "oliveyoung 상품"))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("시험에 쓸 상품 주소를 확인해 주세요")))
            // 필수값 누락과 숫자 아닌 id 도 raw JSON 이 아니라 화면으로 돌아온다
            mockMvc
                .perform(post("/admin/extraction-rules/probe").with(csrf()).param("id", "abc"))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("이름을 입력해 주세요")))
            mockMvc.perform(get("/admin/extraction-rules/abc")).andExpect(redirectedUrl("/admin/extraction-rules?missing"))
            mockMvc.perform(get("/admin/extraction-rules/999999")).andExpect(redirectedUrl("/admin/extraction-rules?missing"))
            mockMvc
                .perform(post("/admin/extraction-rules/999999/delete").with(csrf()))
                .andExpect(redirectedUrl("/admin/extraction-rules?missing"))

            assertTrue(probe.calls.isEmpty())
            assertEquals(0, ruleRepository.count())
        } finally {
            cleanUp()
        }
    }
}
