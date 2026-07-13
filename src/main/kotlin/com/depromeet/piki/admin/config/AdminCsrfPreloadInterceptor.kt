package com.depromeet.piki.admin.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.servlet.HandlerInterceptor

// admin 뷰를 렌더하기 전에 CSRF 토큰을 당겨 온다.
//
// Spring Security 6 의 CsrfToken 은 지연 로드다(SupplierCsrfToken) — 실제로 토큰이 만들어지고 세션에 저장되는
// 시점은 누군가 getToken() 을 처음 부를 때다. Thymeleaf 폼(th:action)이 렌더 도중 그 첫 호출을 하는데,
// 그 시점에 응답 버퍼(기본 8KB)가 이미 넘쳐 커밋됐으면 세션을 새로 만들 수 없어
// IllegalStateException("Cannot create a session after the response has been committed") 로 렌더가 폼 직전에서
// 잘린다. 페이지가 길수록(인라인 CSS·설명문) 폼이 버퍼 밖으로 밀려 터진다.
//
// 운영에서는 AdminAccessFilter 가 세션을 필수로 요구해(없으면 404) 렌더 시점에 세션이 이미 있으므로 드러나지
// 않지만, 세션 없이 뷰에 닿는 경로(로컬 localBypass)에서는 항상 터진다. 렌더 전에 토큰을 확정해 두면 페이지
// 길이와 무관하게 안전하다.
class AdminCsrfPreloadInterceptor : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        // CSRF 제외 경로(/admin/session/**)에는 attribute 가 없다 — 그때는 그냥 넘어간다.
        (request.getAttribute(CsrfToken::class.java.name) as? CsrfToken)?.token
        return true
    }
}
