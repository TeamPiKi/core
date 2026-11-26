package com.depromeet.piki.admin.extraction

import com.depromeet.piki.admin.audit.AdminAuditAction
import com.depromeet.piki.admin.audit.AdminAuditService
import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import com.depromeet.piki.common.exception.BaseException
import com.depromeet.piki.contracts.extraction.v1.ExtractionRule
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.rule.ExtractionRuleEntity
import com.depromeet.piki.product.rule.ExtractionRuleJpaRepository
import com.depromeet.piki.product.rule.ExtractionRuleJson
import com.depromeet.piki.product.rule.ExtractionRuleRevisionEntity
import com.depromeet.piki.product.rule.ExtractionRuleRevisionJpaRepository
import com.depromeet.piki.product.rule.ExtractionRuleStore
import com.depromeet.piki.product.rule.SaveExtractionRuleCommand
import com.depromeet.piki.product.service.remote.ExtractionRuleProbe
import com.depromeet.piki.product.service.remote.ExtractionRuleProbeResult
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.HexFormat

// 몰별 추출 규칙 백오피스의 서비스. 저장 게이트(시험 추출)와 감사 기록을 맡고, 쓰기는 ExtractionRuleStore 에 넘긴다.
//
// 트랜잭션을 두지 않는다 — 시험 추출이 extractor 를 부르는 외부 호출이고(렌더까지 가면 수십 초), 저장은 store 가
// 자기 짧은 트랜잭션으로 한다(AdminExtractionModelService 와 같은 구조).
@Service
@ConditionalOnAdminEnabled
class AdminExtractionRuleService(
    private val ruleRepository: ExtractionRuleJpaRepository,
    private val revisionRepository: ExtractionRuleRevisionJpaRepository,
    private val store: ExtractionRuleStore,
    private val probe: ExtractionRuleProbe,
    private val auditService: AdminAuditService,
) {
    fun list(): List<ExtractionRuleView> = ruleRepository.findAll().sortedBy { it.getId() }.map { ExtractionRuleView.from(it) }

    fun find(id: Long): ExtractionRuleDetail {
        val rule = ruleRepository.findById(id).orElseThrow { IllegalArgumentException(MESSAGE_MISSING) }
        val revisions = revisionRepository.findTop20ByExtractionRuleIdOrderByIdDesc(id)
        return ExtractionRuleDetail(
            rule = ExtractionRuleView.from(rule),
            revisions = revisions.map { ExtractionRuleRevisionView.from(it) },
            lastRevisionId = revisions.firstOrNull()?.getId() ?: 0,
        )
    }

    // 시험 추출의 결과와, "이 본문과 이 주소로 시험한 값을 봤다" 는 확인 값.
    fun probe(draft: ExtractionRuleDraft): ProbedExtractionRule {
        store.check(draft.toCommand(actor = ""))
        val canonical = ExtractionRuleJson.canonical(draft.ruleJson)
        val link = parseLink(draft.sampleUrl)
        return ProbedExtractionRule(probe.probe(link, ExtractionRuleJson.parse(canonical)), confirmationOf(canonical, link))
    }

    // 켠 채 저장하려면 둘을 통과해야 한다.
    //  - 시험 추출이 "이 페이지에서 적용된다" 고 답한다.
    //  - 운영자가 같은 본문·같은 주소의 시험 결과를 이미 봤다(confirmation). 시험 추출은 "읽힌다" 만 알려 주고
    //    "맞는 값이다" 는 사람만 판정할 수 있어서, 값을 한 번도 보지 않고 저장 버튼만 누르는 길을 막는다.
    // 확인이 없거나 본문이 그 뒤 바뀌었으면 저장하지 않고 시험 결과를 돌려준다(SaveOutcome.NeedsConfirmation).
    fun create(
        draft: ExtractionRuleDraft,
        confirmation: String?,
        actor: String,
        clientIp: String?,
    ): SaveOutcome {
        gate(draft, confirmation)?.let { return it }
        val id = store.create(draft.toCommand(actor))
        auditService.record(actor, AdminAuditAction.EXTRACTION_RULE_UPDATE, "#$id ${draft.label.trim()} 생성 (${onOff(draft)})", clientIp)
        return SaveOutcome.Saved(id)
    }

    // baseRevisionId 는 폼을 열었을 때의 마지막 이력 번호다. 그 뒤 다른 운영자가 고쳤으면 덮어쓰지 않는다 —
    // 폼의 켜기 체크가 옛 값이라, 이름만 고쳐 저장해도 남이 방금 끈 규칙이 다시 켜진다.
    fun update(
        id: Long,
        draft: ExtractionRuleDraft,
        confirmation: String?,
        baseRevisionId: Long?,
        actor: String,
        clientIp: String?,
    ): SaveOutcome {
        val current = find(id)
        require(baseRevisionId == current.lastRevisionId) { MESSAGE_STALE }
        gate(draft, confirmation)?.let { return it }
        store.update(id, draft.toCommand(actor))
        auditService.record(actor, AdminAuditAction.EXTRACTION_RULE_UPDATE, "#$id ${draft.label.trim()} 수정 (${onOff(draft)})", clientIp)
        return SaveOutcome.Saved(id)
    }

    fun delete(
        id: Long,
        actor: String,
        clientIp: String?,
    ) {
        val label = find(id).rule.label
        store.delete(id, actor)
        auditService.record(actor, AdminAuditAction.EXTRACTION_RULE_UPDATE, "#$id $label 삭제", clientIp)
    }

    // 끄는 저장은 시험도 확인도 없이 된다. 그것까지 막으면, 몰의 구조가 바뀌어 틀린 값을 내기 시작한 규칙을 끌 수
    // 없게 된다 — 그 순간이 이 화면이 가장 필요한 순간이다.
    private fun gate(
        draft: ExtractionRuleDraft,
        confirmation: String?,
    ): SaveOutcome.NeedsConfirmation? {
        if (!draft.enabled) {
            store.check(draft.toCommand(actor = ""))
            return null
        }
        val probed = probe(draft)
        probed.result.rejection()?.let { throw IllegalArgumentException(it) }
        if (confirmation == probed.confirmation) return null
        return SaveOutcome.NeedsConfirmation(probed)
    }

    // 링크 형식 오류는 공개 API 의 계약 예외(BaseException)로 나온다. 이 화면의 방식(화면 에러)으로 바꾼다
    private fun parseLink(sampleUrl: String): ProductLink =
        try {
            ProductLink.parse(sampleUrl.trim())
        } catch (e: BaseException) {
            throw IllegalArgumentException("시험에 쓸 상품 주소를 확인해 주세요 (https 로 시작하는 상품 페이지 주소).")
        }

    // 비밀이 아니다. 운영자는 공격자가 아니고, 이 값은 "화면에 나온 시험 결과가 지금 저장하려는 본문의 것인가" 만 가른다.
    private fun confirmationOf(
        canonicalRuleJson: String,
        link: ProductLink,
    ): String =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest("$canonicalRuleJson\n${link.value}".toByteArray()),
        )

    private fun onOff(draft: ExtractionRuleDraft): String = if (draft.enabled) "켬" else "끔"

    companion object {
        internal const val MESSAGE_MISSING = "없는 규칙입니다. 다른 운영자가 삭제했을 수 있습니다."
        internal const val MESSAGE_STALE = "이 화면을 연 뒤 다른 운영자가 규칙을 고쳤습니다. 목록에서 다시 열어 주세요."
    }
}

data class ExtractionRuleDraft(
    val label: String,
    val ruleJson: String,
    val sampleUrl: String,
    val enabled: Boolean,
) {
    fun toCommand(actor: String): SaveExtractionRuleCommand =
        SaveExtractionRuleCommand(label = label, ruleJson = ruleJson, sampleUrl = sampleUrl, enabled = enabled, actor = actor)
}

data class ProbedExtractionRule(
    val result: ExtractionRuleProbeResult,
    val confirmation: String,
)

sealed interface SaveOutcome {
    data class Saved(
        val id: Long,
    ) : SaveOutcome

    data class NeedsConfirmation(
        val probed: ProbedExtractionRule,
    ) : SaveOutcome
}

data class ExtractionRuleView(
    val id: Long,
    val label: String,
    // 목록에서 "어느 몰의 규칙인가" 를 보여 주는 한 줄. 본문을 읽지 못하면(손으로 넣은 행) 그 사실을 적는다
    val target: String,
    val render: Boolean,
    val ruleJson: String,
    val sampleUrl: String,
    val enabled: Boolean,
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun from(entity: ExtractionRuleEntity): ExtractionRuleView {
            val rule = runCatching { ExtractionRuleJson.parse(entity.ruleJson) }.getOrNull()
            return ExtractionRuleView(
                id = entity.getId(),
                label = entity.label,
                target = rule?.let { targetOf(it) } ?: "본문을 읽지 못함",
                render = rule?.fetch == ExtractionRule.FetchMode.RENDER,
                ruleJson = ExtractionRuleJson.pretty(entity.ruleJson),
                sampleUrl = entity.sampleUrl,
                enabled = entity.enabled,
                updatedAt = entity.updatedAt,
            )
        }

        private fun targetOf(rule: ExtractionRule): String =
            when (rule.targetCase) {
                ExtractionRule.TargetCase.HOST -> rule.host
                ExtractionRule.TargetCase.MARKER -> "표식 ${rule.marker}"
                else -> "대상 없음"
            }
    }
}

data class ExtractionRuleRevisionView(
    val id: Long,
    val changeType: String,
    val actor: String,
    val enabled: Boolean,
    val ruleJson: String,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun from(entity: ExtractionRuleRevisionEntity): ExtractionRuleRevisionView =
            ExtractionRuleRevisionView(
                id = entity.getId(),
                changeType = entity.changeType,
                actor = entity.actor,
                enabled = entity.enabled,
                ruleJson = ExtractionRuleJson.pretty(entity.ruleJson),
                createdAt = entity.createdAt,
            )
    }
}

data class ExtractionRuleDetail(
    val rule: ExtractionRuleView,
    val revisions: List<ExtractionRuleRevisionView>,
    // 이 규칙의 마지막 이력 번호. 폼이 들고 있다가 저장 때 돌려줘 그 사이의 다른 수정을 알아챈다
    val lastRevisionId: Long,
)
