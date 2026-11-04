package com.depromeet.piki.admin.extraction

import com.depromeet.piki.admin.audit.AdminAuditAction
import com.depromeet.piki.admin.audit.AdminAuditService
import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import com.depromeet.piki.product.service.remote.DbExtractionIdentitySettings
import com.depromeet.piki.product.service.remote.ExtractionIdentityEntity
import com.depromeet.piki.product.service.remote.ExtractionIdentityJpaRepository
import com.depromeet.piki.product.service.remote.ExtractionIdentityRule
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

@Service
@ConditionalOnAdminEnabled
class AdminExtractionIdentityService(
    private val settings: DbExtractionIdentitySettings,
    private val repository: ExtractionIdentityJpaRepository,
    private val auditService: AdminAuditService,
) {
    // 화면은 저장소를 직접 읽는다 — 캐시는 afterCommit 갱신이라 방금 저장한 값이 아직 안 보일 수 있다.
    @Transactional(readOnly = true)
    fun current(): ExtractionIdentityEntity? = settings.findOverride()

    // 빈 칸은 행을 지워 기본 신원으로 되돌린다.
    @Transactional
    fun save(
        rawIdentity: String,
        actor: String,
        clientIp: String?,
    ) {
        val identity = rawIdentity.trim()
        val previous = settings.findOverride()?.identity
        if (identity.isEmpty()) {
            repository.deleteById(ExtractionIdentityEntity.SINGLE_ROW_ID)
        } else {
            ExtractionIdentityRule.violationOf(identity)?.let { throw IllegalArgumentException(it) }
            repository.save(ExtractionIdentityEntity(identity))
        }
        auditService.record(actor, AdminAuditAction.EXTRACTION_IDENTITY_UPDATE, change(previous, identity), clientIp)
        reloadAfterCommit()
    }

    private fun change(
        previous: String?,
        identity: String,
    ): String {
        val before = previous ?: DEFAULT_LABEL
        val after = identity.ifEmpty { DEFAULT_LABEL }
        return if (before == after) "변경 없음" else "$before → $after"
    }

    // 커밋 전 reload 면 이후 단계 롤백 시 캐시만 새 값으로 남아 DB 와 어긋난다.
    private fun reloadAfterCommit() {
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = settings.reload()
            },
        )
    }

    private companion object {
        const val DEFAULT_LABEL = "기본값"
    }
}
