package com.depromeet.piki.admin.logging

import com.depromeet.piki.admin.access.AdminSession
import com.depromeet.piki.admin.config.ClientIp
import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import io.swagger.v3.oas.annotations.Hidden
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

@Hidden
@Controller
@ConditionalOnAdminEnabled
@RequestMapping("/admin/debug-log")
class AdminDebugLogController(
    private val service: AdminDebugLogService,
) {
    @GetMapping
    fun board(view: Model): String {
        view.addAttribute("debugOn", service.isDebugOn())
        return "admin/debug-log"
    }

    @PostMapping
    fun set(
        @RequestParam("debugOn") debugOn: Boolean,
        request: HttpServletRequest,
    ): String {
        service.set(debugOn, actor = AdminSession.actorName(request), clientIp = ClientIp.of(request))
        return "redirect:/admin/debug-log?updated"
    }
}
