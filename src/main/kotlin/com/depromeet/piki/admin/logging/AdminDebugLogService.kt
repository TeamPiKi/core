package com.depromeet.piki.admin.logging

import com.depromeet.piki.admin.audit.AdminAuditAction
import com.depromeet.piki.admin.audit.AdminAuditService
import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import org.springframework.boot.logging.LogLevel
import org.springframework.boot.logging.LoggingSystem
import org.springframework.stereotype.Service

// 우리 패키지만 DEBUG 로 여닫는 스위치(#1109). 메모리에만 있어 재배포하면 INFO 로 돌아간다.
// Spring·Hibernate 까지 열면 로그량이 튀어 Loki 한도를 건드리므로 대상은 이 패키지 하나로 고정한다.
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
