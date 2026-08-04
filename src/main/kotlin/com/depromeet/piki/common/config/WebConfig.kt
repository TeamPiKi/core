package com.depromeet.piki.common.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

// API 레퍼런스 문서(Stoplight UI + 루트 리다이렉트) 노출을 docs.enabled 로 게이팅한다.
// docs.enabled = ${SPRINGDOC_ENABLED:false} (deploy.yml 이 dev 만 true) — dev 만 문서를 노출하고,
// prod 는 이 config 빈 자체가 안 떠 resource handler·리다이렉트가 없으므로 /docs·/ 가 404 다.
// spec(/v3/api-docs)은 springdoc.api-docs.enabled(같은 플래그)로 별도 게이팅된다. 로컬·test 는 test application.yml 이 명시 true.
//
// matchIfMissing = false — 설정 누락 시 fail-closed(문서 비활성). 켜려면 docs.enabled=true 를 명시해야 한다.
//
// Stoplight index.html 은 classpath:/docs-ui/ 에 둔다 — Spring Boot 기본 static 경로(classpath:/static/) 밖이라
// 자동 서빙되지 않고 오직 이 조건부 handler 로만 노출된다(비활성 환경에서 static 잔존 노출을 원천 차단).
@Configuration
@ConditionalOnProperty(name = ["docs.enabled"], havingValue = "true", matchIfMissing = false)
class WebConfig : WebMvcConfigurer {
    // Stoplight UI 정적 자산. classpath:/docs-ui/ 밖에 두어 이 handler 등록 시에만 /docs/** 로 노출된다.
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        registry.addResourceHandler("/docs/**").addResourceLocations("classpath:/docs-ui/")
    }

    // 루트(/) 를 열면 API 문서로 안내한다. 문서 비활성 환경에선 이 config 가 없어 / 가 404(리다이렉트 없음).
    override fun addViewControllers(registry: ViewControllerRegistry) {
        registry.addRedirectViewController("/", "/docs/index.html")
    }
}
