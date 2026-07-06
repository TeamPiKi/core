package com.depromeet.piki.admin.extraction

import com.depromeet.piki.admin.access.AdminSession
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

// 추출 라우팅 정책 관리 화면(#9 디스패처). 목록·추가·삭제(SSR) — AdminTemplateController 와 같은 토대
// (게이트는 슬랙-세션 #526, actor 폴백 "운영자").
@Hidden
@Controller
@ConditionalOnAdminEnabled
@RequestMapping("/admin/extraction-policies")
class AdminExtractionPolicyController(
    private val adminExtractionPolicyService: AdminExtractionPolicyService,
) {
    @GetMapping
    fun list(model: Model): String {
        model.addAttribute("policies", adminExtractionPolicyService.list())
        model.addAttribute("routes", ExtractionRoute.entries)
        return "admin/extraction-policies"
    }

    @PostMapping
    fun add(
        @RequestParam domain: String,
        @RequestParam route: ExtractionRoute,
        @RequestParam(required = false) reason: String?,
        request: HttpServletRequest,
        model: Model,
    ): String =
        try {
            adminExtractionPolicyService.add(domain, route, reason, actor = actor(request), clientIp = clientIp(request))
            "redirect:/admin/extraction-policies?updated"
        } catch (e: IllegalArgumentException) {
            // 정규화·중복 검증 실패 — 제출값을 유지한 채 목록 화면에 에러를 표시한다(400 JSON 대신 SSR).
            model.addAttribute("policies", adminExtractionPolicyService.list())
            model.addAttribute("routes", ExtractionRoute.entries)
            model.addAttribute("error", e.message)
            model.addAttribute("draftDomain", domain)
            model.addAttribute("draftReason", reason)
            "admin/extraction-policies"
        }

    @PostMapping("/delete")
    fun delete(
        @RequestParam domain: String,
        request: HttpServletRequest,
        model: Model,
    ): String =
        try {
            adminExtractionPolicyService.delete(domain, actor = actor(request), clientIp = clientIp(request))
            "redirect:/admin/extraction-policies?deleted"
        } catch (e: IllegalArgumentException) {
            model.addAttribute("policies", adminExtractionPolicyService.list())
            model.addAttribute("routes", ExtractionRoute.entries)
            model.addAttribute("error", e.message)
            "admin/extraction-policies"
        }

    // 감사 actor — Discord 게이트(#526·#654)가 세션에 바인딩한 신원. 게이트를 우회하는 로컬(admin.enabled)엔 세션이 없어 "운영자" 로 폴백.
    private fun actor(request: HttpServletRequest): String =
        request.getSession(false)?.let { AdminSession.actorName(it) } ?: "운영자"

    private fun clientIp(request: HttpServletRequest): String =
        request.getHeader("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()?.ifBlank { null } ?: request.remoteAddr
}
