package com.depromeet.piki.admin.template
import io.swagger.v3.oas.annotations.Hidden

import com.depromeet.piki.admin.access.AdminSession
import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import com.depromeet.piki.common.response.ApiResponseBody
import com.depromeet.piki.notification.domain.NotificationType
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.server.ResponseStatusException

// 알림 템플릿 관리 화면(#250). 목록·편집(SSR) + 라이브 미리보기(AJAX). 게이트(슬랙-세션)는 #526 — 토대 단계는
// admin.enabled 로컬에서만 노출된다. actor 신원도 세션(#526) 전까지 "운영자" 로 둔다.
@Hidden
@Controller
@ConditionalOnAdminEnabled
@RequestMapping("/admin/templates")
class AdminTemplateController(
    private val adminTemplateService: AdminTemplateService,
) {
    @GetMapping
    fun list(model: Model): String {
        model.addAttribute("templates", adminTemplateService.list())
        return "admin/templates"
    }

    @GetMapping("/{type}")
    fun edit(
        @PathVariable type: NotificationType,
        model: Model,
    ): String {
        // ANNOUNCEMENT 내용은 템플릿이 아니라 공지로 관리 — 직접 접근 시 공지 화면으로 보낸다.
        if (type == NotificationType.ANNOUNCEMENT) return "redirect:/admin/announcements"
        model.addAttribute("template", adminTemplateService.get(type))
        return "admin/template-edit"
    }

    @PostMapping("/{type}")
    fun update(
        @PathVariable type: NotificationType,
        @RequestParam title: String,
        @RequestParam(required = false) body: String?,
        // 체크박스는 체크됐을 때만 "true" 를 전송하고 해제 시 파라미터가 아예 안 온다 — 미전송을 off(기본 false)로 받는다.
        @RequestParam(defaultValue = "false") pushEnabled: Boolean,
        request: HttpServletRequest,
        model: Model,
    ): String {
        // ANNOUNCEMENT 는 템플릿이 아니라 공지로 관리한다 — GET edit 뿐 아니라 POST update 도 막아야 우회 수정이 안 된다.
        if (type == NotificationType.ANNOUNCEMENT) return "redirect:/admin/announcements"
        val safeBody = body ?: ""
        return try {
            adminTemplateService.update(type, title, safeBody, pushEnabled, actor = actor(request), clientIp = clientIp(request))
            "redirect:/admin/templates?updated"
        } catch (e: IllegalArgumentException) {
            // 선언 안 된 변수 등 검증 실패 — 제출값을 유지한 채 편집 화면에 에러를 표시한다(400 JSON 대신 SSR).
            model.addAttribute("template", adminTemplateService.get(type).copy(title = title, body = safeBody, pushEnabled = pushEnabled))
            model.addAttribute("error", e.message)
            "admin/template-edit"
        }
    }

    @PostMapping("/{type}/preview")
    @ResponseBody
    fun preview(
        @PathVariable type: NotificationType,
        @RequestParam title: String,
        @RequestParam(required = false) body: String?,
    ): ApiResponseBody<TemplatePreview> {
        if (type == NotificationType.ANNOUNCEMENT) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "ANNOUNCEMENT 템플릿은 미리보기 대상이 아닙니다.")
        }
        // 내부 SSR 엔드포인트도 공통 응답 래퍼로 통일한다.
        return ApiResponseBody.ok(adminTemplateService.preview(type, title, body ?: ""))
    }

    // 감사 actor — Discord 게이트(#526·#654)가 세션에 바인딩한 신원. 게이트를 우회하는 로컬(admin.enabled)엔 세션이 없어 "운영자" 로 폴백.
    private fun actor(request: HttpServletRequest): String =
        request.getSession(false)?.let { AdminSession.actorName(it) } ?: "운영자"

    private fun clientIp(request: HttpServletRequest): String =
        request.getHeader("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()?.ifBlank { null } ?: request.remoteAddr
}
