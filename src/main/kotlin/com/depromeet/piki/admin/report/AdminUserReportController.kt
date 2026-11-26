package com.depromeet.piki.admin.report

import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import io.swagger.v3.oas.annotations.Hidden
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import java.util.UUID

/**
 * 백오피스 신고 화면(#1291). 목록과 대상별 상세 둘이다.
 *
 * 상세를 **신고 한 건이 아니라 신고된 사람 단위**로 둔다 — 운영 판단은 "이 사람이 몇 번, 어떤 사유로
 * 신고됐나" 로 이뤄지고, 한 건만 띄우면 그 패턴이 안 보인다.
 *
 * 제재(정지·삭제) 버튼은 두지 않는다. 이 단계는 확인까지다(#1291 범위).
 *
 * 진입은 디스코드 `/piki-admin env:<env> dest:reports` 로 받는 원타임 링크다. 게이트는 기존
 * AdminAccessFilter(세션·IP)가 그대로 맡는다 — 이 화면만의 인증을 새로 만들지 않는다.
 */
@Hidden
@Controller
@ConditionalOnAdminEnabled
@RequestMapping("/admin/reports")
class AdminUserReportController(
    private val adminUserReportService: AdminUserReportService,
) {
    @GetMapping
    fun board(view: Model): String {
        view.addAttribute("reports", adminUserReportService.recent())
        view.addAttribute("limit", AdminUserReportService.DEFAULT_LIMIT)
        return "admin/reports"
    }

    // 잘못된 id 는 목록으로 되돌린다. SSR 이라 예외를 그대로 올리면 @RestControllerAdvice 가 화면을
    // raw JSON 으로 갈아치워 운영자가 페이지를 잃는다
    @GetMapping("/{reportedId}")
    fun detail(
        @PathVariable reportedId: String,
        view: Model,
    ): String {
        val userId = runCatching { UUID.fromString(reportedId) }.getOrNull() ?: return "redirect:/admin/reports?missing"
        val detail = adminUserReportService.detailOf(userId) ?: return "redirect:/admin/reports?missing"
        view.addAttribute("detail", detail)
        return "admin/report-detail"
    }
}
