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
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

// 추출 라우팅 정책 관리 화면(#9 디스패처). 갈래별 3열 보드(목록·필터)와 상세(수정·삭제) 두 화면의 SSR —
// AdminTemplateController 의 목록 → 편집 진입과 같은 토대 (게이트는 슬랙-세션 #526,
// actor 폴백은 AdminSession.actorName(request)).
// 파괴적 액션(삭제)은 보드에 두지 않는다. 상세로 들어와 도메인·정책·사유를 확인한 뒤 실행한다 —
// 목록 인라인 삭제 + confirm() 은 오클릭이 곧 즉시 적용(차단 해제)이라 되돌릴 창이 없었다.
@Hidden
@Controller
@ConditionalOnAdminEnabled
@RequestMapping("/admin/extraction-policies")
class AdminExtractionPolicyController(
    private val adminExtractionPolicyService: AdminExtractionPolicyService,
) {
    // guide: 상세 화면이 "정책 종류 설명" 링크로 보낼 때 그 설명을 펼친 채 연다.
    @GetMapping
    fun board(
        @RequestParam(required = false) route: String?,
        @RequestParam(required = false) guide: String?,
        model: Model,
    ): String = boardView(route, model, guideOpen = !guide.isNullOrBlank())

    // 추가 폼은 헤드리스 허가를 켜지 않는다(save 의 기본값 = 거부). 허가는 "메일로 받아 원장에 남기는" 조작이라
    // 근거 입력·현재 상태 확인이 있는 상세 화면의 몫이다 — 목록의 한 줄짜리 폼에서 지나가듯 켤 일이 아니다.
    // 그래서 여기서 HEADLESS_FIRST 를 고르면 default-deny 가드에 걸려 "상세에서 허가를 켜라"는 안내가 뜬다.
    @PostMapping
    fun add(
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
            // 정규화·길이 검증 실패 — 제출값(route 선택 포함)을 유지한 채 보드 화면에 에러를 표시한다(400 JSON 대신 SSR).
            model.addAttribute("error", e.message)
            model.addAttribute("draftDomain", domain)
            model.addAttribute("draftReason", reason)
            boardView(filter = null, model = model, selectedRoute = route.name)
        }

    @GetMapping("/{domain}")
    fun detail(
        @PathVariable domain: String,
        model: Model,
    ): String =
        try {
            detailView(adminExtractionPolicyService.find(domain), model)
        } catch (e: IllegalArgumentException) {
            // 다른 운영자가 방금 지웠거나 URL 을 손으로 친 경우 — 띄울 대상이 없으므로 보드로 돌린다.
            "redirect:/admin/extraction-policies?missing"
        }

    // 상세의 정책·사유·헤드리스 허가 수정. save 가 upsert 라 추가 폼과 같은 경로를 탄다 (도메인은 PK 라 상세에서
    // 바꾸지 않는다). 정책과 사유를 한 폼으로 받는 이유: 정책이 바뀌는 순간이 곧 근거가 새로 필요한 순간이라, 둘이
    // 따로 저장되면 "403 봇 차단" 사유가 SUPPORTED 행에 남는 식으로 근거가 정책과 어긋난다. 허가도 같은 폼에 둔다 —
    // 허가와 정책이 따로 저장되면 "허가 없는 HEADLESS_FIRST" 같은 어긋난 중간 상태를 지나야 한다.
    //
    // headlessAllowed 는 체크박스라 끄면 파라미터 자체가 오지 않는다 — 없음을 곧 거부(false)로 읽는다(default-deny).
    @PostMapping("/{domain}")
    fun update(
        @PathVariable domain: String,
        @RequestParam route: ExtractionRoute,
        @RequestParam(required = false) reason: String?,
        @RequestParam(required = false) headlessAllowed: Boolean?,
        @RequestParam(required = false) permissionRef: String?,
        request: HttpServletRequest,
        model: Model,
    ): String =
        try {
            adminExtractionPolicyService.save(
                domain,
                route,
                reason,
                actor = AdminSession.actorName(request),
                clientIp = ClientIp.of(request),
                headlessAllowed = headlessAllowed ?: false,
                permissionRef = permissionRef,
            )
            "redirect:/admin/extraction-policies?updated"
        } catch (e: IllegalArgumentException) {
            model.addAttribute("error", e.message)
            model.addAttribute("draftReason", reason)
            detailView(
                adminExtractionPolicyService.find(domain),
                model,
                selectedRoute = route.name,
                draftHeadlessAllowed = headlessAllowed ?: false,
                draftPermissionRef = permissionRef,
            )
        }

    @PostMapping("/{domain}/delete")
    fun delete(
        @PathVariable domain: String,
        request: HttpServletRequest,
        model: Model,
    ): String =
        try {
            adminExtractionPolicyService.delete(domain, actor = AdminSession.actorName(request), clientIp = ClientIp.of(request))
            "redirect:/admin/extraction-policies?deleted"
        } catch (e: IllegalArgumentException) {
            model.addAttribute("error", e.message)
            boardView(filter = null, model = model)
        }

    // 보드 화면의 모델 채우기 단일 지점 — 정상 목록과 두 에러 재표시 경로가 공유한다
    // (한쪽만 갱신돼 에러 화면에서 모델이 비는 함정 방지).
    // selectedRoute 기본값이 SUPPORTED 인 이유: 추가 폼의 기본 선택이 곧 오조작 시 저장되는 값이라,
    // 라우팅을 바꾸지 않는 값(기록용)을 기본에 둔다. UNSUPPORTED 가 기본이면 실수 한 번이 등록 차단이 된다.
    // guideOpen 은 모델로 넘긴다. 템플릿의 th:attr 안에서는 요청 파라미터(param) 접근이 막혀 있다.
    private fun boardView(
        filter: String?,
        model: Model,
        selectedRoute: String? = null,
        guideOpen: Boolean = false,
    ): String {
        model.addAttribute("board", adminExtractionPolicyService.board(parseRoute(filter)))
        model.addAttribute("routes", ExtractionRoute.entries)
        model.addAttribute("selectedRoute", selectedRoute ?: ExtractionRoute.SUPPORTED.name)
        model.addAttribute("guideOpen", guideOpen)
        return "admin/extraction-policies"
    }

    // knownRoute: 저장된 route 문자열이 이 바이너리의 enum 에 있는가. 없으면(신버전이 만든 값 → 구버전 롤백)
    // select 에 고를 항목이 없으므로 화면이 경고를 띄우고 운영자가 알려진 정책으로 교체하거나 삭제하게 한다.
    //
    // 허가 입력값은 selectedRoute 와 같은 방식으로 컨트롤러가 확정해 넘긴다 — 에러 재표시에서는 방금 제출한
    // 값을, 평소에는 저장된 값을 보여준다(체크 해제는 draft 가 false 라 그대로 유지된다).
    private fun detailView(
        policy: ExtractionPolicyView,
        model: Model,
        selectedRoute: String? = null,
        draftHeadlessAllowed: Boolean? = null,
        draftPermissionRef: String? = null,
    ): String {
        model.addAttribute("policy", policy)
        model.addAttribute("routes", ExtractionRoute.entries)
        model.addAttribute("selectedRoute", selectedRoute ?: policy.route)
        model.addAttribute("knownRoute", ExtractionRoute.entries.any { it.name == policy.route })
        model.addAttribute("headlessAllowedChecked", draftHeadlessAllowed ?: policy.headlessAllowed)
        model.addAttribute("permissionRefValue", draftPermissionRef ?: policy.permissionRef)
        return "admin/extraction-policy-detail"
    }

    // ?route= 는 tolerant 하게 읽는다 — 모르는 값이면 400 대신 "필터 없음(전체)"으로 떨군다. 옛 링크를 눌렀거나
    // URL 을 손으로 고쳤을 때 화면이 깨지는 것보다, 전체 보드를 보여주는 편이 백오피스에서 덜 위험하다.
    private fun parseRoute(raw: String?): ExtractionRoute? = ExtractionRoute.entries.find { it.name == raw }
}
