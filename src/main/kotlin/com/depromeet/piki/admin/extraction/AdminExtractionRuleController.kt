package com.depromeet.piki.admin.extraction

import com.depromeet.piki.admin.access.AdminSession
import com.depromeet.piki.admin.config.ClientIp
import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import io.swagger.v3.oas.annotations.Hidden
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

// 몰별 추출 규칙 관리 화면(#1318). 목록과 편집 두 화면의 SSR 이고 토대는 AdminExtractionPolicyController 와 같다.
//
// 편집 화면의 폼 하나에 버튼이 둘이다. "시험 추출" 은 저장하지 않고 extractor 가 읽은 값을 보여 준다. "저장" 은
// 그 값을 본 본문만 저장한다 — 값을 본 적 없는 본문이면 저장하지 않고 시험 결과부터 보여 준다.
// 삭제는 목록에 두지 않는다. 규칙이 사라지면 그 몰의 추출이 곧바로 공통 규칙과 LLM 으로 내려가므로, 편집 화면에서
// 본문을 확인한 뒤 실행한다.
//
// 파라미터를 전부 String 으로 받는다. 타입 변환·필수값 실패는 컨트롤러에 닿기 전에 raw JSON 400 이 되어 운영자가
// 쓰던 화면을 잃는다(.claude/rules/web-request-boundary.md 의 SSR 항목).
@Hidden
@Controller
@ConditionalOnAdminEnabled
@RequestMapping("/admin/extraction-rules")
class AdminExtractionRuleController(
    private val adminExtractionRuleService: AdminExtractionRuleService,
) {
    @GetMapping
    fun list(model: Model): String {
        model.addAttribute("rules", adminExtractionRuleService.list())
        return "admin/extraction-rules"
    }

    @GetMapping("/new")
    fun newForm(model: Model): String = formView(model, id = null, draft = ExtractionRuleDraft("", "", "", enabled = true))

    @GetMapping("/{id}")
    fun detail(
        @PathVariable id: String,
        model: Model,
    ): String {
        val detail = findOrNull(id) ?: return REDIRECT_MISSING
        val rule = detail.rule
        return formView(model, rule.id, ExtractionRuleDraft(rule.label, rule.ruleJson, rule.sampleUrl, rule.enabled))
    }

    // id 는 고치던 규칙의 것(새 규칙이면 없음). 시험만 하고 저장하지 않으므로 어느 폼으로 돌아갈지 정하는 데만 쓴다.
    @PostMapping("/probe")
    fun probe(
        @RequestParam(defaultValue = "") id: String,
        @RequestParam(defaultValue = "") label: String,
        @RequestParam(defaultValue = "") ruleJson: String,
        @RequestParam(defaultValue = "") sampleUrl: String,
        @RequestParam(defaultValue = "") enabled: String,
        @RequestParam(defaultValue = "") baseRevisionId: String,
        model: Model,
    ): String {
        val draft = ExtractionRuleDraft(label, ruleJson, sampleUrl, enabled = enabled.isNotBlank())
        val ruleId = id.toLongOrNull()
        return try {
            formView(model, ruleId, draft, probed = adminExtractionRuleService.probe(draft), baseRevisionId = baseRevisionId)
        } catch (e: IllegalArgumentException) {
            formView(model, ruleId, draft, error = e.message, baseRevisionId = baseRevisionId)
        }
    }

    @PostMapping
    fun create(
        @RequestParam(defaultValue = "") label: String,
        @RequestParam(defaultValue = "") ruleJson: String,
        @RequestParam(defaultValue = "") sampleUrl: String,
        @RequestParam(defaultValue = "") enabled: String,
        @RequestParam(defaultValue = "") confirmation: String,
        request: HttpServletRequest,
        model: Model,
    ): String {
        val draft = ExtractionRuleDraft(label, ruleJson, sampleUrl, enabled = enabled.isNotBlank())
        return try {
            val outcome =
                adminExtractionRuleService.create(draft, confirmation, AdminSession.actorName(request), ClientIp.of(request))
            outcomeView(outcome, model, id = null, draft = draft, baseRevisionId = "")
        } catch (e: IllegalArgumentException) {
            formView(model, id = null, draft = draft, error = e.message)
        }
    }

    @PostMapping("/{id}")
    fun update(
        @PathVariable id: String,
        @RequestParam(defaultValue = "") label: String,
        @RequestParam(defaultValue = "") ruleJson: String,
        @RequestParam(defaultValue = "") sampleUrl: String,
        @RequestParam(defaultValue = "") enabled: String,
        @RequestParam(defaultValue = "") confirmation: String,
        @RequestParam(defaultValue = "") baseRevisionId: String,
        request: HttpServletRequest,
        model: Model,
    ): String {
        val ruleId = id.toLongOrNull() ?: return REDIRECT_MISSING
        val draft = ExtractionRuleDraft(label, ruleJson, sampleUrl, enabled = enabled.isNotBlank())
        return try {
            val outcome =
                adminExtractionRuleService.update(
                    ruleId,
                    draft,
                    confirmation,
                    baseRevisionId.toLongOrNull(),
                    AdminSession.actorName(request),
                    ClientIp.of(request),
                )
            outcomeView(outcome, model, ruleId, draft, baseRevisionId)
        } catch (e: IllegalArgumentException) {
            formView(model, ruleId, draft, error = e.message, baseRevisionId = baseRevisionId)
        }
    }

    @PostMapping("/{id}/delete")
    fun delete(
        @PathVariable id: String,
        request: HttpServletRequest,
    ): String {
        val ruleId = id.toLongOrNull() ?: return REDIRECT_MISSING
        return try {
            adminExtractionRuleService.delete(ruleId, actor = AdminSession.actorName(request), clientIp = ClientIp.of(request))
            "redirect:/admin/extraction-rules?deleted"
        } catch (e: IllegalArgumentException) {
            REDIRECT_MISSING
        }
    }

    private fun outcomeView(
        outcome: SaveOutcome,
        model: Model,
        id: Long?,
        draft: ExtractionRuleDraft,
        baseRevisionId: String,
    ): String =
        when (outcome) {
            is SaveOutcome.Saved -> "redirect:/admin/extraction-rules/${outcome.id}?saved"
            is SaveOutcome.NeedsConfirmation ->
                formView(model, id, draft, probed = outcome.probed, needsConfirmation = true, baseRevisionId = baseRevisionId)
        }

    private fun findOrNull(id: String): ExtractionRuleDetail? {
        val ruleId = id.toLongOrNull() ?: return null
        return runCatching { adminExtractionRuleService.find(ruleId) }.getOrNull()
    }

    // 편집 화면의 모델 채우기 단일 지점 — 정상 표시와 시험 결과·에러 재표시가 공유한다.
    // 이력은 저장된 규칙에만 있다. 그 사이 규칙이 지워졌으면 제출값을 든 채 새 규칙 폼으로 떨어뜨린다 —
    // 복구 경로에서 예외가 새면 화면이 raw JSON 으로 바뀌어 쓰던 본문을 잃는다(#988 과 같은 함정).
    //
    // baseRevisionId 는 폼을 처음 열었을 때의 값을 끝까지 들고 간다. 재표시 때 지금 값으로 바꾸면, 그 사이의 다른
    // 수정을 알아채려는 대조가 늘 통과해 버린다.
    private fun formView(
        model: Model,
        id: Long?,
        draft: ExtractionRuleDraft,
        probed: ProbedExtractionRule? = null,
        needsConfirmation: Boolean = false,
        error: String? = null,
        baseRevisionId: String? = null,
    ): String {
        val detail = id?.let { findOrNull(it.toString()) }
        model.addAttribute("ruleId", detail?.rule?.id)
        model.addAttribute("revisions", detail?.revisions ?: emptyList<ExtractionRuleRevisionView>())
        model.addAttribute("updatedAt", detail?.rule?.updatedAt)
        model.addAttribute("baseRevisionId", baseRevisionId?.ifBlank { null } ?: detail?.lastRevisionId?.toString() ?: "")
        model.addAttribute("draft", draft)
        model.addAttribute("probe", probed?.result)
        model.addAttribute("probeRejection", probed?.result?.rejection())
        model.addAttribute("confirmation", probed?.confirmation ?: "")
        model.addAttribute("needsConfirmation", needsConfirmation)
        model.addAttribute("error", error)
        return "admin/extraction-rule-form"
    }

    companion object {
        private const val REDIRECT_MISSING = "redirect:/admin/extraction-rules?missing"
    }
}
