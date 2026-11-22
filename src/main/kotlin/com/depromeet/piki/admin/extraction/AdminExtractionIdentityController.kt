package com.depromeet.piki.admin.extraction

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
@RequestMapping("/admin/extraction-identity")
class AdminExtractionIdentityController(
    private val adminExtractionIdentityService: AdminExtractionIdentityService,
) {
    @GetMapping
    fun board(view: Model): String = boardView(view)

    @PostMapping
    fun save(
        @RequestParam("identity") identity: String,
        request: HttpServletRequest,
        view: Model,
    ): String =
        try {
            adminExtractionIdentityService.save(
                identity,
                actor = AdminSession.actorName(request),
                clientIp = ClientIp.of(request),
            )
            "redirect:/admin/extraction-identity?updated"
        } catch (e: IllegalArgumentException) {
            view.addAttribute("draftIdentity", identity)
            view.addAttribute("error", e.message)
            boardView(view)
        }

    private fun boardView(view: Model): String {
        view.addAttribute("current", adminExtractionIdentityService.current())
        return "admin/extraction-identity"
    }
}
