package com.depromeet.piki.admin.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.env.Environment
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.ModelAndView

// 모든 admin 뷰 헤더에 환경(env)·접속 IP 를 주입한다. 어느 환경(dev/staging/prod) 데이터를 보고 있는지, 어느 IP 로
// 접속했는지(게이트 allowlist 키)를 헤더에서 바로 구분하게 한다. 공통 헤더 fragment(admin/fragments :: topbar)가 읽는다.
class AdminHeaderInterceptor(
    private val adminProperties: AdminProperties,
    private val environment: Environment,
) : HandlerInterceptor {
    override fun postHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        modelAndView: ModelAndView?,
    ) {
        val mav = modelAndView ?: return // @ResponseBody(JSON 폴링 등)는 mav 가 없다
        if (mav.viewName?.startsWith("redirect:") == true) return // 리다이렉트엔 헤더를 그리지 않는다
        mav.addObject("adminEnv", currentEnv())
        mav.addObject("adminClientIp", ClientIp.of(request))
        // 로컬 우회에서는 세션이 없는 게 정상이고 접속 IP(게이트 allowlist 키)도 의미가 없다.
        // 헤더가 "세션 만료됨"·loopback IP 를 보여주며 오해를 사지 않도록, fragment 가 이 플래그로 그 둘을 감춘다.
        mav.addObject("adminLocalBypass", adminProperties.localBypass)
    }

    private fun currentEnv(): String =
        resolveEnv(
            localBypass = adminProperties.localBypass,
            isDevProfile = environment.activeProfiles.contains("dev"),
            environmentGate = adminProperties.environmentGate,
        )

    companion object {
        // 프로파일은 dev / (staging·prod 공유)prod 라 staging·prod 가 안 갈리므로 environment-gate(dev·staging=true,
        // prod=false)를 함께 본다. 로컬은 localBypass 로 가린다.
        fun resolveEnv(
            localBypass: Boolean,
            isDevProfile: Boolean,
            environmentGate: Boolean,
        ): String =
            when {
                localBypass -> "LOCAL"
                isDevProfile -> "DEV"
                environmentGate -> "STAGING"
                else -> "PROD"
            }
    }
}
