package com.depromeet.piki.admin.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher
import org.springframework.security.web.util.matcher.OrRequestMatcher

/**
 * 백오피스 경로(/admin·/admin-access)를 메인 JWT(stateless) 체인(@Order(2))에서 떼어내는 체인.
 *
 * 접근 제어는 이 체인이 아니라 그 앞단 서블릿 필터(EnvironmentAccessFilter·AdminAccessFilter, #526)가 한다 —
 * 슬랙으로 검증된 세션 + IP allowlist. 따라서 이 체인은 admin 경로를 메인 체인에서 떼어 permitAll 로 통과시키고
 * 세션·CSRF 만 관리한다(필터가 이미 막은 뒤라 여기선 인증을 또 걸지 않는다).
 *
 * admin-access 경로는 슬랙 진입(서명검증으로 보호되는 공개 표면)이라 CSRF 를 제외한다 — 슬랙 외부 POST 는
 * CSRF 토큰을 실을 수 없다. admin 폼(Thymeleaf)은 CSRF 를 유지한다(_csrf 히든).
 */
@Configuration
@ConditionalOnAdminEnabled
class AdminSecurityConfig {
    @Bean
    @Order(1)
    fun adminSecurityFilterChain(http: HttpSecurity): SecurityFilterChain =
        http
            // PathPatternRequestMatcher 로 명시한다 — 순수 경로 매칭이라 같은 prefix 안의 모든 (path, method) 가
            // 결정적으로 이 체인에 묶인다(String 다중 패턴 조합에서 GET /admin-access/grant 가 메인 체인으로 새 401 나던 문제 차단).
            .securityMatcher(adminPathMatcher())
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .csrf {
                // /admin-access/** 만 CSRF 제외한다 — 슬랙·디스코드 진입이라 우리 페이지에서 토큰을 실을 수 없고,
                // 대신 서명검증으로 보호된다. 나머지 /admin/** 은 폼(Thymeleaf _csrf 히든)이든 fetch 든 CSRF 를 유지한다.
                //
                // /admin/session/** 를 제외 목록에서 뺐다(#988) — "fetch 라 토큰을 실을 수 없다"는 전제가 틀렸다.
                // 같은 admin 화면의 다른 fetch(템플릿 미리보기·공지 이미지 업로드)는 이미 헤더로 토큰을 싣고 있다.
                // 제외해 두면 제3자 페이지가 cross-origin 폼 자동 제출로 extend 를 호출해 allowlist TTL 을 무기한
                // 늘릴 수 있어, 연장은 명시적 버튼으로만 한다는 #669 의 보장이 무력화된다(GET /ttl 은 안전 메서드라 무관).
                it.ignoringRequestMatchers(ADMIN_PATHS.matcher("/admin-access/**"))
            }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED) }
            .build()

    // 4개 admin 경로를 OrRequestMatcher 하나로 묶어 securityMatcher(RequestMatcher) 단일 인자로 넘긴다 —
    // String vararg 오버로드 모호성을 피하고, 순수 경로 매칭이라 모든 (path, method) 가 결정적으로 이 체인에 묶인다.
    private fun adminPathMatcher(): OrRequestMatcher =
        OrRequestMatcher(
            ADMIN_PATHS.matcher("/admin"),
            ADMIN_PATHS.matcher("/admin/**"),
            ADMIN_PATHS.matcher("/admin-access/**"),
        )

    companion object {
        // 서블릿 기본 PathPattern 매처 — 경로만으로 결정적 매칭(MVC 핸들러 introspection 없음).
        private val ADMIN_PATHS = PathPatternRequestMatcher.withDefaults()
    }
}
