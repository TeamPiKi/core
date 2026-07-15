package com.depromeet.piki.admin.access

import com.depromeet.piki.admin.config.AdminProperties
import com.depromeet.piki.admin.config.ClientIp
import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

// dev/staging 에서 API 레퍼런스 문서(/docs·/v3/api-docs)와 actuator 만 allowlist IP 게이트로 막는다(#733). prod 는 off.
// 과거엔 도메인 전체를 막았으나, 백엔드 API 는 열어두고(현상유지) "문서 노출 차단"으로 범위를 좁혔다 — dev 문서는
// /docs Discord 커맨드로 grant 받은 IP 만 접근하고, staging 문서는 springdoc off 로 어차피 404 다(문서 자체 없음).
// staging 이 prod 프로파일을 공유하므로(deploy.yml) 프로파일이 아니라 env 플래그(admin.environment-gate)로 켠다 —
// 배포가 dev/staging 에만 ENV_ACCESS_GATE=true 를 준다. AdminAccessFilter 와 같은 allowlist·grant 등록 흐름을 공유한다.
//
// 게이트 밖(통과): 백엔드 API·grant 진입(/admin-access/**)·health·localhost. grant 받은 IP·localhost 는 게이트를 통과한다.
@Component
@ConditionalOnAdminEnabled
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
class EnvironmentAccessFilter(
    private val allowlistService: AdminAllowlistService,
    private val adminProperties: AdminProperties,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val ip = ClientIp.of(request)
        if (isLocalhost(ip) || allowlistService.isAllowed(ip)) {
            allowlistService.refresh(ip) // sliding (localhost 는 키가 없어 no-op)
            filterChain.doFilter(request, response)
            return
        }
        // setStatus 로 막는다(sendError 금지) — sendError 의 /error 디스패치가 메인 체인에서 401 로 바뀌는 걸 피한다.
        response.status = HttpServletResponse.SC_NOT_FOUND
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        if (!adminProperties.environmentGate) return true // prod·로컬: 게이트 off
        return !isGatedPath(request.requestURI)
    }

    private fun isLocalhost(ip: String): Boolean = ip == "127.0.0.1" || ip == "::1" || ip == "0:0:0:0:0:0:0:1"

    companion object {
        // 게이트 대상 경로 — API 레퍼런스 문서(/docs·/v3/api-docs)와 actuator 만. 나머지(백엔드 API·grant 진입·health)는 통과한다(#733).
        //  - 문서: 전 엔드포인트·스키마의 외부 노출 차단. dev 는 grant 받은 IP 만, staging 은 springdoc off 로 404.
        //  - actuator: nginx 403 + 127.0.0.1 바인딩에 더한 앱 레벨 2중 방어(doFilterInternal 의 isLocalhost 로 내부 Alloy scrape 만 허용).
        fun isGatedPath(uri: String): Boolean =
            uri.startsWith("/docs") || uri.startsWith("/v3/api-docs") || uri.startsWith("/actuator")
    }
}
