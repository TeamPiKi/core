package com.depromeet.piki.admin.config

import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

// admin 공통 인터셉터를 /admin/** 에 등록한다 — 렌더 전 CSRF 토큰 확정, 렌더 후 헤더(환경·접속 IP) 주입.
// admin 빈 게이트(ConditionalOnAdminEnabled) 아래에서만 로드돼, admin 이 꺼진 환경에선 인터셉터 자체가 등록되지 않는다.
@Configuration
@ConditionalOnAdminEnabled
class AdminWebConfig(
    private val adminProperties: AdminProperties,
    private val environment: Environment,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        // CSRF preload 가 먼저다 — 뷰 렌더(th:action)가 커밋된 응답에서 세션을 만들려다 폼 직전에서 잘리는 것을 막는다.
        registry
            .addInterceptor(AdminCsrfPreloadInterceptor())
            .addPathPatterns("/admin/**")
        registry
            .addInterceptor(AdminHeaderInterceptor(adminProperties, environment))
            .addPathPatterns("/admin/**")
    }
}
