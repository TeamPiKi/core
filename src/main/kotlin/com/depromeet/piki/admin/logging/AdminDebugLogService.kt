package com.depromeet.piki.admin.logging

import com.depromeet.piki.admin.audit.AdminAuditAction
import com.depromeet.piki.admin.audit.AdminAuditService
import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import org.springframework.boot.logging.LogLevel
import org.springframework.boot.logging.LoggingSystem
import org.springframework.stereotype.Service

// 대상은 우리 패키지 하나다. 라이브러리까지 DEBUG 로 열면 로그량이 Loki 한도를 건드린다.
@Service
@ConditionalOnAdminEnabled
class AdminDebugLogService(
    private val loggingSystem: LoggingSystem,
    private val auditService: AdminAuditService,
) {
    fun isDebugOn(): Boolean = loggingSystem.getLoggerConfiguration(TARGET_PACKAGE)?.effectiveLevel == LogLevel.DEBUG

    fun set(
        debugOn: Boolean,
        actor: String,
        clientIp: String?,
    ) {
        val before = isDebugOn()
        loggingSystem.setLogLevel(TARGET_PACKAGE, if (debugOn) LogLevel.DEBUG else LogLevel.INFO)
        auditService.record(actor, AdminAuditAction.DEBUG_LOG_UPDATE, "${label(before)} → ${label(debugOn)}", clientIp)
    }

    private fun label(debugOn: Boolean) = if (debugOn) "DEBUG" else "INFO"

    companion object {
        const val TARGET_PACKAGE = "com.depromeet.piki"
    }
}
