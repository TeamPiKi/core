package com.depromeet.piki.admin.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.env.Environment
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.ModelAndView

// 모든 admin 뷰 헤더에 환경(env)·접속 IP·API 문서 노출 여부를 주입한다. 어느 환경(dev/prod) 데이터를 보고 있는지, 어느 IP 로
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
        // API 레퍼런스 문서(/docs) 바로가기 노출 여부. 문서가 실제로 서빙되는 환경에서만 링크를 그린다 —
        // prod 는 docs.enabled=false 라 WebConfig 빈 자체가 없어 /docs 가 404 다(죽은 링크 방지).
        mav.addObject("adminDocsEnabled", docsEnabled())
    }

    // WebConfig 의 @ConditionalOnProperty(havingValue="true", matchIfMissing=false) 와 같은 판정을 쓴다 —
    // String?.toBoolean() 은 미설정(null)·"false"·임의값이 false, "true"(대소문자 무관)만 true 라 그 조건과 일치한다.
    // 플래그(docs.enabled = ${SPRINGDOC_ENABLED:false})를 두 곳이 각자 해석하지 않도록 판정 방식을 맞춘 것.
    private fun docsEnabled(): Boolean = environment.getProperty("docs.enabled").toBoolean()

    private fun currentEnv(): String =
        resolveEnv(
            localBypass = adminProperties.localBypass,
            isDevProfile = environment.activeProfiles.contains("dev"),
        )

    companion object {
        // 배포 환경은 프로파일로 갈린다(dev=dev, prod=prod). 로컬은 localBypass 로 가린다.
        fun resolveEnv(
            localBypass: Boolean,
            isDevProfile: Boolean,
        ): String =
            when {
                localBypass -> "LOCAL"
                isDevProfile -> "DEV"
                else -> "PROD"
            }
    }
}
