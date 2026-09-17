package com.depromeet.piki.admin.logging

import com.depromeet.piki.admin.access.AdminSession
import com.depromeet.piki.admin.config.AdminProperties
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
    private val adminProperties: AdminProperties,
) {
    @GetMapping
    fun board(view: Model): String {
        view.addAttribute("debugOn", service.isDebugOn())
        view.addAttribute("environment", adminProperties.environment.ifBlank { "local" })
        return "admin/debug-log"
    }

    // 체크박스는 꺼진 상태를 보내지 않으므로 파라미터 부재가 곧 OFF 다.
    @PostMapping
    fun set(
        @RequestParam("debugOn", required = false, defaultValue = "false") debugOn: Boolean,
        request: HttpServletRequest,
    ): String {
        service.set(debugOn, actor = AdminSession.actorName(request), clientIp = ClientIp.of(request))
        return "redirect:/admin/debug-log?updated"
    }
}
