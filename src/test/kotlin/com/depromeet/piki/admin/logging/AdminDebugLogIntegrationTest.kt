package com.depromeet.piki.admin.logging

import com.depromeet.piki.admin.audit.AdminAuditAction
import com.depromeet.piki.admin.audit.AdminAuditLogRepository
import com.depromeet.piki.support.IntegrationTestSupport
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.logging.LogLevel
import org.springframework.boot.logging.LoggingSystem
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// 로그 레벨은 JVM 전역 상태라 트랜잭션 롤백으로 되돌아가지 않는다. 각 테스트가 finally 에서 INFO 로 복귀시킨다.
class AdminDebugLogIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var loggingSystem: LoggingSystem

    @Autowired
    private lateinit var auditLogRepository: AdminAuditLogRepository

    private val serviceLogger = LoggerFactory.getLogger("com.depromeet.piki.notification.sse.LocalSseDelivery")
    private val libraryLogger = LoggerFactory.getLogger("org.hibernate.SQL")

    @Test
    fun `켜면 서비스 패키지만 DEBUG 가 되고 라이브러리 로거는 그대로다`() {
        val mockMvc = buildMockMvc()
        assertFalse(serviceLogger.isDebugEnabled)

        try {
            mockMvc
                .perform(post("/admin/debug-log").with(csrf()).param("debugOn", "true"))
                .andExpect(redirectedUrl("/admin/debug-log?updated"))

            assertTrue(serviceLogger.isDebugEnabled)
            assertFalse(libraryLogger.isDebugEnabled)
            mockMvc
                .perform(get("/admin/debug-log"))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("DEBUG 켜짐")))
        } finally {
            restoreInfo()
        }
    }

    @Test
    fun `끄면 INFO 로 돌아가고 켜고 끈 기록이 감사 로그에 남는다`() {
        val mockMvc = buildMockMvc()
        val before = auditCount()

        try {
            mockMvc.perform(post("/admin/debug-log").with(csrf()).param("debugOn", "true"))
            mockMvc.perform(post("/admin/debug-log").with(csrf()).param("debugOn", "false"))

            assertFalse(serviceLogger.isDebugEnabled)
            assertEquals(before + 2, auditCount())
            assertEquals(
                listOf("INFO → DEBUG", "DEBUG → INFO"),
                auditLogRepository.findAll().filter { it.action == AdminAuditAction.DEBUG_LOG_UPDATE.name }.takeLast(2).map { it.detail },
            )
        } finally {
            restoreInfo()
        }
    }

    private fun auditCount() = auditLogRepository.findAll().count { it.action == AdminAuditAction.DEBUG_LOG_UPDATE.name }

    private fun restoreInfo() = loggingSystem.setLogLevel(AdminDebugLogService.TARGET_PACKAGE, LogLevel.INFO)

    private fun buildMockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()
}
