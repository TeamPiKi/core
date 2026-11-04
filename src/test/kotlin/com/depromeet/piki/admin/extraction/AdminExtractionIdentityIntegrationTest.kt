package com.depromeet.piki.admin.extraction

import com.depromeet.piki.admin.audit.AdminAuditAction
import com.depromeet.piki.admin.audit.AdminAuditLogRepository
import com.depromeet.piki.product.service.remote.DbExtractionIdentitySettings
import com.depromeet.piki.product.service.remote.ExtractionIdentityJpaRepository
import com.depromeet.piki.support.IntegrationTestSupport
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import kotlin.test.assertEquals
import kotlin.test.assertNull

// @Transactional 자동 롤백을 쓰지 않는다 — 저장이 afterCommit 에 캐시 reload 를 걸고 설정 행이 서비스에 하나뿐이다
// (AdminItemQuotaIntegrationTest 와 같은 이유). 각 테스트가 자기 뒷정리를 하고 reload 한다.
class AdminExtractionIdentityIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var repository: ExtractionIdentityJpaRepository

    @Autowired
    private lateinit var settings: DbExtractionIdentitySettings

    @Autowired
    private lateinit var auditLogRepository: AdminAuditLogRepository

    @Test
    fun `저장하면 배포 없이 추출 요청에 실을 값이 바뀌고 감사 로그에 남는다`() {
        try {
            save("Foo/2.0").andExpect(redirectedUrl("/admin/extraction-identity?updated"))

            assertEquals("Foo/2.0", settings.identityOverride())
            assertEquals("기본값 → Foo/2.0", latestAuditDetail())
        } finally {
            cleanUp()
        }
    }

    @Test
    fun `빈 칸으로 저장하면 행을 지워 기본 신원으로 돌아간다`() {
        try {
            save("Foo/2.0").andExpect(redirectedUrl("/admin/extraction-identity?updated"))

            save("  ").andExpect(redirectedUrl("/admin/extraction-identity?updated"))

            assertNull(settings.identityOverride())
            assertNull(repository.findAll().firstOrNull())
            assertEquals("Foo/2.0 → 기본값", latestAuditDetail())
        } finally {
            cleanUp()
        }
    }

    @Test
    fun `같은 값을 다시 저장하면 행이 하나로 유지되고 변경 없음으로 남는다`() {
        try {
            save("Foo/2.0")

            save("Foo/2.0")

            assertEquals(1, repository.count())
            assertEquals("변경 없음", latestAuditDetail())
        } finally {
            cleanUp()
        }
    }

    @Test
    fun `규칙을 어긴 값은 저장되지 않고 제출값과 사유가 화면에 남는다`() {
        try {
            save("pikibot")
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("bot, crawler, spider 가 든 이름은 쓸 수 없습니다.")))
                .andExpect(content().string(containsString("value=\"pikibot\"")))

            assertNull(repository.findAll().firstOrNull())
            assertNull(settings.identityOverride())
        } finally {
            cleanUp()
        }
    }

    @Test
    fun `화면이 지금 값을 보여주고 홈에서 들어갈 수 있다`() {
        try {
            save("Foo/2.0")

            mockMvc()
                .perform(get("/admin/extraction-identity"))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("value=\"Foo/2.0\"")))
                .andExpect(content().string(containsString("maxlength=\"40\"")))
            mockMvc()
                .perform(get("/admin"))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("/admin/extraction-identity")))
        } finally {
            cleanUp()
        }
    }

    private fun mockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

    private fun save(identity: String) =
        mockMvc().perform(post("/admin/extraction-identity").with(csrf()).param("identity", identity))

    private fun latestAuditDetail(): String? =
        auditLogRepository
            .findAll()
            .lastOrNull { it.action == AdminAuditAction.EXTRACTION_IDENTITY_UPDATE.name }
            ?.detail

    private fun cleanUp() {
        repository.deleteAll()
        settings.reload()
    }
}
