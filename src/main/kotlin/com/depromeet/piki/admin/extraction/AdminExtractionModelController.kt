package com.depromeet.piki.admin.extraction

import com.depromeet.piki.admin.access.AdminSession
import com.depromeet.piki.admin.config.ClientIp
import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import com.depromeet.piki.product.service.remote.ExtractionTarget
import io.swagger.v3.oas.annotations.Hidden
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

// 추출 모델 관리 화면(#875). 경로(LINK · IMAGE)별로 어떤 LLM 모델을 쓸지 배포 없이 지정한다 —
// AdminExtractionPolicyController 와 같은 토대(게이트는 슬랙-세션 #526, actor 는 AdminSession.actorName).
//
// 목록 하나로 끝난다(상세 화면 없음). 행이 경로 수(2개)로 고정이고 각 행이 값 하나뿐이라, 상세로 들어갈 것이
// 없다. 파괴적 액션인 해제도 목록에 두되 그 결과는 "extractor 기본 모델로 되돌아감"이라 되돌릴 수 있다
// (라우팅 정책의 삭제가 곧 차단 해제였던 것과 달리 위험이 낮다).
//
// Spring 의 Model 파라미터를 view 로 받는 이유: 이 화면의 도메인 용어가 "model"(LLM 모델)이라 폼 필드명과
// 이름이 겹친다. 폼 필드 쪽이 사용자 대면이므로 그 이름을 지키고 프레임워크 쪽을 비켰다.
@Hidden
@Controller
@ConditionalOnAdminEnabled
@RequestMapping("/admin/extraction-models")
class AdminExtractionModelController(
    private val adminExtractionModelService: AdminExtractionModelService,
) {
    @GetMapping
    fun board(view: Model): String = boardView(view)

    @PostMapping("/{target}")
    fun save(
        @PathVariable target: ExtractionTarget,
        @RequestParam("model") rawModel: String,
        request: HttpServletRequest,
        view: Model,
    ): String =
        try {
            adminExtractionModelService.save(
                target,
                rawModel,
                actor = AdminSession.actorName(request),
                clientIp = ClientIp.of(request),
            )
            "redirect:/admin/extraction-models?updated"
        } catch (e: IllegalArgumentException) {
            // 검증 실패와 프로브 거절이 같은 자리로 온다 — 둘 다 "이 값은 저장할 수 없다"는 같은 결론이고,
            // 사유는 예외 메시지가 구분해 준다. 제출값을 유지한 채 목록에 에러를 표시한다.
            view.addAttribute("draftTarget", target)
            view.addAttribute("draftModel", rawModel)
            errorView(e, view)
        }

    @PostMapping("/{target}/check")
    fun check(
        @PathVariable target: ExtractionTarget,
        view: Model,
    ): String =
        try {
            adminExtractionModelService.check(target)
            "redirect:/admin/extraction-models?checked"
        } catch (e: IllegalArgumentException) {
            errorView(e, view)
        }

    @PostMapping("/{target}/clear")
    fun clear(
        @PathVariable target: ExtractionTarget,
        request: HttpServletRequest,
        view: Model,
    ): String =
        try {
            adminExtractionModelService.clear(
                target,
                actor = AdminSession.actorName(request),
                clientIp = ClientIp.of(request),
            )
            "redirect:/admin/extraction-models?cleared"
        } catch (e: IllegalArgumentException) {
            errorView(e, view)
        }

    // 목록 모델 채우기 단일 지점 — 정상 진입과 모든 에러 재표시가 공유한다(한쪽만 갱신돼 에러 화면에서
    // 목록이 비는 함정 방지).
    private fun boardView(view: Model): String {
        view.addAttribute("rows", adminExtractionModelService.board())
        return "admin/extraction-models"
    }

    private fun errorView(
        e: IllegalArgumentException,
        view: Model,
    ): String {
        view.addAttribute("error", e.message)
        return boardView(view)
    }
}
