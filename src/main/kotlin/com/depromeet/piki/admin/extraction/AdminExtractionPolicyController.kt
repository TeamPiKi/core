package com.depromeet.piki.admin.extraction

import com.depromeet.piki.admin.access.AdminSession
import com.depromeet.piki.admin.config.ClientIp
import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import com.depromeet.piki.product.routing.ExtractionRoute
import io.swagger.v3.oas.annotations.Hidden
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

// 추출 라우팅 정책 관리 화면(#9 디스패처). 목록·저장(upsert)·삭제(SSR) — AdminTemplateController 와 같은 토대
// (게이트는 슬랙-세션 #526, actor 폴백은 AdminSession.actorName(request)).
@Hidden
@Controller
@ConditionalOnAdminEnabled
@RequestMapping("/admin/extraction-policies")
class AdminExtractionPolicyController(
    private val adminExtractionPolicyService: AdminExtractionPolicyService,
) {
    @GetMapping
    fun list(model: Model): String = listView(model)

    @PostMapping
    fun save(
        @RequestParam domain: String,
        @RequestParam route: ExtractionRoute,
        @RequestParam(required = false) reason: String?,
        request: HttpServletRequest,
        model: Model,
    ): String =
        try {
            adminExtractionPolicyService.save(domain, route, reason, actor = AdminSession.actorName(request), clientIp = ClientIp.of(request))
            "redirect:/admin/extraction-policies?updated"
        } catch (e: IllegalArgumentException) {
            // 정규화·길이 검증 실패 — 제출값(route 선택 포함)을 유지한 채 목록 화면에 에러를 표시한다(400 JSON 대신 SSR).
            model.addAttribute("error", e.message)
            model.addAttribute("draftDomain", domain)
            model.addAttribute("draftRoute", route)
            model.addAttribute("draftReason", reason)
            listView(model)
        }

    @PostMapping("/delete")
    fun delete(
        @RequestParam domain: String,
        request: HttpServletRequest,
        model: Model,
    ): String =
        try {
            adminExtractionPolicyService.delete(domain, actor = AdminSession.actorName(request), clientIp = ClientIp.of(request))
            "redirect:/admin/extraction-policies?deleted"
        } catch (e: IllegalArgumentException) {
            model.addAttribute("error", e.message)
            listView(model)
        }

    // 목록 화면의 모델 채우기 단일 지점 — 정상 목록과 두 에러 재표시 경로가 공유한다
    // (한쪽만 갱신돼 에러 화면에서 모델이 비는 함정 방지).
    private fun listView(model: Model): String {
        model.addAttribute("policies", adminExtractionPolicyService.list())
        model.addAttribute("routes", ExtractionRoute.entries)
        return "admin/extraction-policies"
    }
}
