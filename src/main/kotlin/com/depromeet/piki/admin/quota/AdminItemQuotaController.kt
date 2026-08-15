package com.depromeet.piki.admin.quota

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

// 아이템 등록 한도 화면(#934). 지금 얼마나 쓰고 있는지 보고, 한도를 배포 없이 조절한다 —
// AdminExtractionModelController 와 같은 토대(게이트는 슬랙-세션 #526, actor 는 AdminSession.actorName).
//
// 목록 하나로 끝난다(상세 화면 없음). 설정이 한 벌뿐이라 상세로 들어갈 것이 없고, 계정 사용량 조회도
// 같은 화면의 폼 하나로 처리해 "현황을 보다가 바로 조인다" 는 흐름이 끊기지 않게 한다.
@Hidden
@Controller
@ConditionalOnAdminEnabled
@RequestMapping("/admin/item-quota")
class AdminItemQuotaController(
    private val adminItemQuotaService: AdminItemQuotaService,
) {
    @GetMapping
    fun board(view: Model): String = boardView(view)

    // 계정 사용량 조회 — 조회일 뿐이라 GET 이고, 결과를 같은 화면에 얹는다.
    @GetMapping("/usage")
    fun usage(
        @RequestParam("userId") rawUserId: String,
        view: Model,
    ): String =
        try {
            view.addAttribute("userUsage", adminItemQuotaService.usageOf(rawUserId))
            view.addAttribute("draftUserId", rawUserId)
            boardView(view)
        } catch (e: IllegalArgumentException) {
            view.addAttribute("draftUserId", rawUserId)
            errorView(e, view)
        }

    // 빈 칸은 "그 노브를 기본값으로" 다. 네 칸을 한 번에 받으므로 제출된 화면이 곧 최종 상태다.
    @PostMapping
    fun save(
        @RequestParam("enabled", required = false) enabled: Boolean?,
        @RequestParam("userLimit", required = false) userLimit: Int?,
        @RequestParam("capacityLimit", required = false) capacityLimit: Int?,
        @RequestParam("capacityAlertPercent", required = false) capacityAlertPercent: Int?,
        request: HttpServletRequest,
        view: Model,
    ): String =
        try {
            adminItemQuotaService.save(
                ItemQuotaSettingsForm(enabled, userLimit, capacityLimit, capacityAlertPercent),
                actor = AdminSession.actorName(request),
                clientIp = ClientIp.of(request),
            )
            "redirect:/admin/item-quota?updated"
        } catch (e: IllegalArgumentException) {
            // 제출값을 유지한 채 목록에 에러를 표시한다(AdminExtractionModelController.save 와 같은 결).
            view.addAttribute("draftEnabled", enabled)
            view.addAttribute("draftUserLimit", userLimit)
            view.addAttribute("draftCapacityLimit", capacityLimit)
            view.addAttribute("draftCapacityAlertPercent", capacityAlertPercent)
            errorView(e, view)
        }

    @PostMapping("/reset")
    fun reset(
        request: HttpServletRequest,
        view: Model,
    ): String =
        try {
            adminItemQuotaService.reset(
                actor = AdminSession.actorName(request),
                clientIp = ClientIp.of(request),
            )
            "redirect:/admin/item-quota?reset"
        } catch (e: IllegalArgumentException) {
            errorView(e, view)
        }

    // 목록 모델 채우기 단일 지점 — 정상 진입과 모든 에러 재표시가 공유한다(한쪽만 갱신돼 에러 화면에서
    // 현황이 비는 함정 방지).
    private fun boardView(view: Model): String {
        view.addAttribute("board", adminItemQuotaService.board())
        return "admin/item-quota"
    }

    private fun errorView(
        e: IllegalArgumentException,
        view: Model,
    ): String {
        view.addAttribute("error", e.message)
        return boardView(view)
    }
}
