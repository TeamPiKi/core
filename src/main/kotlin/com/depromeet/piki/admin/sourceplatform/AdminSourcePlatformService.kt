package com.depromeet.piki.admin.sourceplatform

import com.depromeet.piki.admin.audit.AdminAuditAction
import com.depromeet.piki.admin.audit.AdminAuditService
import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import com.depromeet.piki.product.source.DbSourcePlatformResolver
import com.depromeet.piki.product.source.SourcePlatformEntity
import com.depromeet.piki.product.source.SourcePlatformJpaRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.net.URI
import java.net.URISyntaxException
import java.time.LocalDateTime

// 백오피스 출처 몰 표시명 관리(#766). 배포 없이 도메인별 표시명(위시 응답 sourcePlatform)을 저장(upsert)·삭제한다.
// 수정 시: 도메인 정규화·검증 → 저장/삭제 → 캐시 reload(커밋 후) → 감사 기록 (AdminExtractionPolicyService 패턴).
@Service
@ConditionalOnAdminEnabled
class AdminSourcePlatformService(
    private val sourcePlatformRepository: SourcePlatformJpaRepository,
    private val sourcePlatformResolver: DbSourcePlatformResolver,
    private val auditService: AdminAuditService,
) {
    @Transactional(readOnly = true)
    fun list(): List<SourcePlatformView> = sourcePlatformRepository.findAll().map { it.toView() }.sortedBy { it.domain }

    @Transactional(readOnly = true)
    fun find(rawDomain: String): SourcePlatformView =
        sourcePlatformRepository
            .findById(normalize(rawDomain))
            .orElseThrow { IllegalArgumentException("등록된 표시명이 없는 도메인입니다: $rawDomain") }
            .toView()

    private fun SourcePlatformEntity.toView() = SourcePlatformView(domain = domain, displayName = displayName, updatedAt = updatedAt)

    // upsert — 같은 도메인이 있으면 표시명을 교체한다. "삭제 후 재추가"로 수정하게 하면 그 사이 fallback 표시명이
    // 나가는 공백 창이 생기고, 중복 검사 후 저장의 check-then-act 레이스도 남는다 — 교체 의미로 두면 둘 다 사라진다.
    @Transactional
    fun save(
        rawDomain: String,
        rawDisplayName: String,
        actor: String,
        clientIp: String?,
    ) {
        val domain = normalize(rawDomain)
        val displayName = rawDisplayName.trim()
        require(displayName.isNotBlank()) { "표시명을 입력해 주세요." }
        validateLengths(domain, displayName)
        val replaced = sourcePlatformRepository.existsById(domain)
        try {
            sourcePlatformRepository.saveAndFlush(SourcePlatformEntity(domain = domain, displayName = displayName))
        } catch (e: DataIntegrityViolationException) {
            // 같은 신규 도메인이 동시에 저장되면(수동 @Id 라 둘 다 INSERT 시도) 늦은 쪽이 PK 충돌로 떨어진다 —
            // 전역 핸들러의 500 대신 목록 화면 에러로 흡수한다. flush 를 여기서 강제해 커밋 시점이 아니라 이 자리에서 잡는다.
            throw IllegalArgumentException("같은 도메인이 방금 저장됐습니다. 목록을 확인하고 다시 시도해 주세요.")
        }
        auditService.record(
            actor,
            AdminAuditAction.SOURCE_PLATFORM_UPDATE,
            "$domain → $displayName ${if (replaced) "교체" else "추가"}",
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
        val entity = sourcePlatformRepository.findById(domain).orElseThrow { IllegalArgumentException("등록된 표시명이 없는 도메인입니다: $domain") }
        sourcePlatformRepository.delete(entity)
        auditService.record(actor, AdminAuditAction.SOURCE_PLATFORM_UPDATE, "$domain → ${entity.displayName} 삭제", clientIp)
        reloadAfterCommit()
    }

    // 입력 정규화 + 검증 — 매칭(matchesAnyDomain)은 정규형(소문자·trailing dot 없음)을 전제하므로 경계인 여기가 책임진다.
    // URL 을 통째로 붙여넣는 실수(스킴·경로 포함)는 도메인만 남기라고 안내한다(IllegalArgumentException → 목록 화면 에러 표시).
    private fun normalize(rawDomain: String): String {
        val domain = rawDomain.trim().trimEnd('.').lowercase()
        require(domain.isNotBlank()) { "도메인을 입력해 주세요." }
        require(!domain.contains('/') && !domain.contains(':') && !domain.contains(' ')) {
            "도메인만 입력해 주세요 (스킴·경로 제외, 예: 29cm.co.kr)"
        }
        require(domain.contains('.')) { "올바른 도메인 형식이 아닙니다 (예: 29cm.co.kr)" }
        // URL host 로 해석될 수 없는 문자열(쿼리 문자 포함·빈 라벨 등)은 저장은 되지만 어떤 URL host 와도 매칭되지
        // 않는 유령 행이 된다 — 운영 화면에선 성공으로 보여 더 위험하다. host 파싱 결과가 입력과 정확히 일치할 때만 통과.
        require(domain == parseHost(domain)) { "올바른 도메인 형식이 아닙니다 (예: 29cm.co.kr)" }
        return domain
    }

    private fun parseHost(domain: String): String? =
        try {
            URI("https://$domain").host
        } catch (e: URISyntaxException) {
            null
        }

    // DB 컬럼 한계(둘 다 255)를 서비스에서 먼저 막아 운영자 입력 실수를 편집 화면 에러로 처리한다.
    // 안 막으면 커밋 시점 DB 제약 위반으로 500 이 난다 (AdminExtractionPolicyService.validateLengths 와 같은 이유).
    private fun validateLengths(
        domain: String,
        displayName: String,
    ) {
        require(domain.length <= COLUMN_MAX_LENGTH) { "도메인은 ${COLUMN_MAX_LENGTH}자를 초과할 수 없습니다." }
        require(displayName.length <= COLUMN_MAX_LENGTH) { "표시명은 ${COLUMN_MAX_LENGTH}자를 초과할 수 없습니다." }
    }

    // 캐시 갱신은 커밋 후로 미룬다 — 커밋 전 reload 면 이후 단계 롤백 시 캐시만 새 표시명으로 남아 DB 와 어긋난다.
    private fun reloadAfterCommit() {
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = sourcePlatformResolver.reload()
            },
        )
    }

    companion object {
        private const val COLUMN_MAX_LENGTH = 255
    }
}

data class SourcePlatformView(
    val domain: String,
    val displayName: String,
    val updatedAt: LocalDateTime,
)
