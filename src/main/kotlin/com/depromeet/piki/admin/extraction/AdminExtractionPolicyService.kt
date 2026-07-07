package com.depromeet.piki.admin.extraction

import com.depromeet.piki.admin.audit.AdminAuditAction
import com.depromeet.piki.admin.audit.AdminAuditService
import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import com.depromeet.piki.product.routing.DbExtractionRoutingPolicy
import com.depromeet.piki.product.routing.ExtractionPlatformPolicyEntity
import com.depromeet.piki.product.routing.ExtractionPlatformPolicyJpaRepository
import com.depromeet.piki.product.routing.ExtractionRoute
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.LocalDateTime

// 백오피스 추출 라우팅 정책 관리(#9 디스패처). 배포 없이 도메인별 차단(UNSUPPORTED)·브라우저 직행(HEADLESS_FIRST)을
// 저장(upsert)·삭제한다. 수정 시: 도메인 정규화·검증 → 저장/삭제 → 캐시 reload(커밋 후) → 감사 기록 (AdminTemplateService 패턴).
@Service
@ConditionalOnAdminEnabled
class AdminExtractionPolicyService(
    private val policyRepository: ExtractionPlatformPolicyJpaRepository,
    private val routingPolicy: DbExtractionRoutingPolicy,
    private val auditService: AdminAuditService,
) {
    @Transactional(readOnly = true)
    fun list(): List<ExtractionPolicyView> =
        policyRepository
            .findAll()
            .sortedWith(compareBy({ it.route }, { it.domain }))
            .map { ExtractionPolicyView(domain = it.domain, route = it.route, reason = it.reason, updatedAt = it.updatedAt) }

    // upsert — 같은 도메인이 있으면 정책을 교체한다. "삭제 후 재추가"로 수정하게 하면 그 사이 정책 공백 창이 생기고
    // (삭제 시점에 캐시가 즉시 갱신돼 기본 체인으로 열림), 중복 검사 후 저장의 check-then-act 레이스도 남는다 —
    // 교체 의미로 두면 둘 다 사라진다.
    @Transactional
    fun save(
        rawDomain: String,
        route: ExtractionRoute,
        reason: String?,
        actor: String,
        clientIp: String?,
    ) {
        val domain = normalize(rawDomain)
        val trimmedReason = reason?.trim()?.ifBlank { null }
        validateLengths(domain, trimmedReason)
        val replaced = policyRepository.existsById(domain)
        policyRepository.save(ExtractionPlatformPolicyEntity(domain = domain, route = route.name, reason = trimmedReason))
        auditService.record(
            actor,
            AdminAuditAction.EXTRACTION_POLICY_UPDATE,
            "$domain → $route ${if (replaced) "교체" else "추가"}",
            clientIp,
        )
        reloadAfterCommit()
    }

    @Transactional
    fun delete(
        rawDomain: String,
        actor: String,
        clientIp: String?,
    ) {
        val domain = normalize(rawDomain)
        val entity = policyRepository.findById(domain).orElseThrow { IllegalArgumentException("정책이 없는 도메인입니다: $domain") }
        policyRepository.delete(entity)
        auditService.record(actor, AdminAuditAction.EXTRACTION_POLICY_UPDATE, "$domain → ${entity.route} 삭제", clientIp)
        reloadAfterCommit()
    }

    // 입력 정규화 + 검증 — 매칭(matchesAnyDomain)은 정규형(소문자·trailing dot 없음)을 전제하므로 경계인 여기가 책임진다.
    // URL 을 통째로 붙여넣는 실수(스킴·경로 포함)는 도메인만 남기라고 안내한다(IllegalArgumentException → 목록 화면 에러 표시).
    private fun normalize(rawDomain: String): String {
        val domain = rawDomain.trim().trimEnd('.').lowercase()
        require(domain.isNotBlank()) { "도메인을 입력해 주세요." }
        require(!domain.contains('/') && !domain.contains(':') && !domain.contains(' ')) {
            "도메인만 입력해 주세요 (스킴·경로 제외, 예: coupang.com)"
        }
        require(domain.contains('.')) { "올바른 도메인 형식이 아닙니다 (예: coupang.com)" }
        return domain
    }

    // DB 컬럼 한계(둘 다 255)를 서비스에서 먼저 막아 운영자 입력 실수를 편집 화면 에러로 처리한다.
    // 안 막으면 커밋 시점 DB 제약 위반으로 500 이 난다 (AdminTemplateService.validateLengths 와 같은 이유).
    private fun validateLengths(
        domain: String,
        reason: String?,
    ) {
        require(domain.length <= COLUMN_MAX_LENGTH) { "도메인은 ${COLUMN_MAX_LENGTH}자를 초과할 수 없습니다." }
        require((reason?.length ?: 0) <= COLUMN_MAX_LENGTH) { "사유는 ${COLUMN_MAX_LENGTH}자를 초과할 수 없습니다." }
    }

    // 캐시 갱신은 커밋 후로 미룬다 — 커밋 전 reload 면 이후 단계 롤백 시 캐시만 새 정책으로 남아 DB 와 어긋난다.
    private fun reloadAfterCommit() {
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = routingPolicy.reload()
            },
        )
    }

    companion object {
        private const val COLUMN_MAX_LENGTH = 255
    }
}

data class ExtractionPolicyView(
    val domain: String,
    val route: String,
    val reason: String?,
    val updatedAt: LocalDateTime,
)
