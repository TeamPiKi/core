package com.depromeet.piki.metrics.dashboard

import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import io.swagger.v3.oas.annotations.Hidden
import jakarta.servlet.http.HttpServletResponse
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import com.depromeet.piki.metrics.report.WeeklyReportService
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// 운영 통계 대시보드(admin). 슬랙-IP 게이트(/admin/**)·admin.enabled 아래에서만 노출. 프리셋(preset)이나 직접 지정
// (from·to, datetime-local: yyyy-MM-ddTHH:mm)으로 구간을 잡고, 현재 구간 vs 직전 동일 길이 구간 비교도 함께 내린다.
@Hidden
@Controller
@ConditionalOnAdminEnabled
@RequestMapping("/admin/metrics")
class MetricsController(
    private val metricsService: MetricsService,
    private val weeklyReportService: WeeklyReportService,
) {
    @GetMapping
    fun dashboard(
        @RequestParam(required = false) preset: String?,
        @RequestParam(required = false)
        @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
        from: LocalDateTime?,
        @RequestParam(required = false)
        @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
        to: LocalDateTime?,
        // 기본 true = 개발진(developers 명단) 제외. 페이지 토글로 false 면 개발진 포함해 다시 집계한다.
        @RequestParam(defaultValue = "true") excludeInternal: Boolean,
        model: Model,
    ): String {
        val range = metricsService.resolveRange(preset, from, to)
        model.addAttribute("snapshot", metricsService.snapshot(range.from, range.to, excludeInternal))
        model.addAttribute("comparison", metricsService.compareWithPrevious(range.from, range.to, excludeInternal))
        model.addAttribute("activePreset", range.preset)
        model.addAttribute("excludeInternal", excludeInternal)
        return "admin/metrics"
    }

    // 대시보드를 자체완결 HTML 파일로 내려보낸다(팀 공유용). 데이터는 dashboard 와 동일하게 snapshot + comparison 을
    // 재사용하고, admin/metrics-export 템플릿이 외부 자산 없이(인라인 CSS · JS 없음) 렌더해 받는 사람이 브라우저로 열거나
    // 그대로 인쇄(⌘P)해 PDF 로 저장할 수 있다. Content-Disposition attachment 라 브라우저가 파일로 내려받는다.
    @GetMapping("/export", produces = [MediaType.TEXT_HTML_VALUE])
    fun export(
        @RequestParam(required = false) preset: String?,
        @RequestParam(required = false)
        @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
        from: LocalDateTime?,
        @RequestParam(required = false)
        @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
        to: LocalDateTime?,
        // 내려받는 리포트가 화면과 같은 기준이 되도록 토글 상태를 그대로 전달받는다.
        @RequestParam(defaultValue = "true") excludeInternal: Boolean,
        response: HttpServletResponse,
        model: Model,
    ): String {
        val range = metricsService.resolveRange(preset, from, to)
        val snapshot = metricsService.snapshot(range.from, range.to, excludeInternal)
        model.addAttribute("snapshot", snapshot)
        model.addAttribute("comparison", metricsService.compareWithPrevious(range.from, range.to, excludeInternal))
        model.addAttribute("excludeInternal", excludeInternal)
        model.addAttribute("generatedAt", LocalDateTime.now(KST))

        val filename = "piki-metrics-${snapshot.from.format(FILE_TS)}_${snapshot.to.format(FILE_TS)}.html"
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
        return "admin/metrics-export"
    }

    // 주간 지표 리포트를 지금 즉시 Discord metrics 채널에 게시한다(지난 완결 주 기준).
    // 스케줄(화 10시)과 별개의 수동 트리거 — 첫 발사·스케줄러 miss 시 재발송용. admin 세션 게이트로 보호(백오피스).
    // redirect-after-post(다른 admin POST 액션과 동일) — 새로고침 재발송을 막고 대시보드로 돌아가 배너로 결과를 알린다.
    @PostMapping("/weekly-report")
    fun sendWeeklyReport(): String {
        weeklyReportService.sendLastCompleteWeek()
        return "redirect:/admin/metrics?reportSent=1"
    }

    companion object {
        private val KST = ZoneId.of("Asia/Seoul")
        private val FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
    }
}
