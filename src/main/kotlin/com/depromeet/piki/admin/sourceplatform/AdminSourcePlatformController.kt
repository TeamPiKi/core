package com.depromeet.piki.admin.sourceplatform

import com.depromeet.piki.admin.access.AdminSession
import com.depromeet.piki.admin.config.ClientIp
import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import com.depromeet.piki.product.source.SourcePlatformFallback
import io.swagger.v3.oas.annotations.Hidden
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

// 출처 몰 표시명 관리 화면(#766). 목록(추가 폼 포함)과 상세(수정·삭제) 두 화면의 SSR —
// AdminExtractionPolicyController 의 목록 → 편집 진입과 같은 토대 (게이트는 슬랙-세션 #526,
// actor 폴백은 AdminSession.actorName(request)).
// 파괴적 액션(삭제)은 목록에 두지 않는다. 상세로 들어와 도메인·표시명을 확인한 뒤 실행한다.
@Hidden
@Controller
@ConditionalOnAdminEnabled
@RequestMapping("/admin/source-platforms")
class AdminSourcePlatformController(
    private val adminSourcePlatformService: AdminSourcePlatformService,
) {
    @GetMapping
    fun list(model: Model): String = listView(model)

    @PostMapping
    fun add(
        @RequestParam domain: String,
        @RequestParam displayName: String,
        request: HttpServletRequest,
        model: Model,
    ): String =
        try {
            adminSourcePlatformService.save(domain, displayName, actor = AdminSession.actorName(request), clientIp = ClientIp.of(request))
            "redirect:/admin/source-platforms?updated"
        } catch (e: IllegalArgumentException) {
            // 정규화·길이 검증 실패 — 제출값을 유지한 채 목록 화면에 에러를 표시한다(400 JSON 대신 SSR).
            model.addAttribute("error", e.message)
            model.addAttribute("draftDomain", domain)
            model.addAttribute("draftDisplayName", displayName)
            listView(model)
        }

    @GetMapping("/{domain}")
    fun detail(
        @PathVariable domain: String,
        model: Model,
    ): String =
        try {
            detailView(adminSourcePlatformService.find(domain), model)
        } catch (e: IllegalArgumentException) {
            // 다른 운영자가 방금 지웠거나 URL 을 손으로 친 경우 — 띄울 대상이 없으므로 목록으로 돌린다.
            "redirect:/admin/source-platforms?missing"
        }

    // 상세의 표시명 수정. save 가 upsert 라 추가 폼과 같은 경로를 탄다 (도메인은 PK 라 상세에서 바꾸지 않는다).
    @PostMapping("/{domain}")
    fun update(
        @PathVariable domain: String,
        @RequestParam displayName: String,
        request: HttpServletRequest,
        model: Model,
    ): String =
        try {
            adminSourcePlatformService.save(domain, displayName, actor = AdminSession.actorName(request), clientIp = ClientIp.of(request))
            "redirect:/admin/source-platforms?updated"
        } catch (e: IllegalArgumentException) {
            // find 가 다시 던질 수 있다 — 정규화를 통과 못 하는 domain(경로에 공백 등)이면 save 와 같은 이유로 막힌다.
            // 복구 경로에서 새 예외가 새면 화면이 raw JSON 400 으로 갈린다(#988). 그 땐 목록으로 돌린다.
            runCatching { adminSourcePlatformService.find(domain) }
                .map { platform ->
                    model.addAttribute("error", e.message)
                    model.addAttribute("draftDisplayName", displayName)
                    detailView(platform, model)
                }.getOrElse { "redirect:/admin/source-platforms?missing" }
        }

    @PostMapping("/{domain}/delete")
    fun delete(
        @PathVariable domain: String,
        request: HttpServletRequest,
        model: Model,
    ): String =
        try {
            adminSourcePlatformService.delete(domain, actor = AdminSession.actorName(request), clientIp = ClientIp.of(request))
            "redirect:/admin/source-platforms?deleted"
        } catch (e: IllegalArgumentException) {
            model.addAttribute("error", e.message)
            listView(model)
        }

    // 목록 화면의 모델 채우기 단일 지점 — 정상 목록과 에러 재표시 경로가 공유한다
    // (한쪽만 갱신돼 에러 화면에서 모델이 비는 함정 방지).
    private fun listView(model: Model): String {
        model.addAttribute("platforms", adminSourcePlatformService.list())
        return "admin/source-platforms"
    }

    private fun detailView(
        platform: SourcePlatformView,
        model: Model,
    ): String {
        model.addAttribute("platform", platform)
        // 삭제 박스가 추상 설명 대신 "삭제하면 이 값이 된다"를 보여주게, 이 도메인의 실제 fallback 을 계산해 내린다.
        // (도메인은 정규형으로 저장돼 있어 fallback 의 host 전제와 일치한다.)
        model.addAttribute("fallbackName", SourcePlatformFallback.of(platform.domain))
        return "admin/source-platform-detail"
    }
}
