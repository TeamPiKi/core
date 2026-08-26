package com.depromeet.piki.admin.access

import com.depromeet.piki.admin.config.AdminProperties
import com.depromeet.piki.admin.config.ClientIp
import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import com.depromeet.piki.common.logging.LoggingKeys
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.annotation.Order
import org.springframework.session.web.http.SessionRepositoryFilter
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.UrlPathHelper

// prod·전 환경의 /admin 게이트 — 슬랙으로 검증된 세션 + allowlist IP + 세션-IP 바인딩 셋 다 맞아야 통과, 아니면 404.
// password 가 아니라 "슬랙 링크 클릭으로 발급된 세션"이 신원이다. 미허용은 401/302 가 아니라 404(존재 숨김).
// 공개 진입(/admin-access/**)은 경로가 달라 이 필터 대상이 아니다(shouldNotFilter).
//
// order: SessionRepositoryFilter 바로 안쪽이어야 한다(#891). 세션이 Redis 로 옮겨간 뒤(#885/#888)
// getSession 은 그 필터가 씌우는 요청 래퍼를 통해서만 저장소에 닿는다 — 바깥에서 부르면 래퍼가 없는 원본 요청이라
// 톰캣 인메모리(빈) 세션을 조회해 항상 null 이고, 게이트가 grant 직후에도 404 를 낸다. 그 전엔 세션이 톰캣
// 인메모리라 순서와 무관하게 읽혀 이 의존이 드러나지 않았다. 상수를 직접 참조해 Spring 이 기본값을 바꿔도 따라간다.
//
// 이 값(MIN_VALUE+51)은 기존 제약도 그대로 지킨다 — 관측·TraceIdHeader(HIGHEST+1,+2)·AccessLog(+3) 안쪽이라
// traceId 와 access log 가 이 요청을 감싸고, Security(-100)보다는 한참 바깥이라 메인 JWT 체인에 닿기 전에 끊는다.
// localBypass(로컬 개발)면 게이트를 건너뛴다.
@Component
@ConditionalOnAdminEnabled
@Order(SessionRepositoryFilter.DEFAULT_ORDER + 1)
class AdminAccessFilter(
    private val allowlistService: AdminAllowlistService,
    private val adminProperties: AdminProperties,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (adminProperties.localBypass) {
            filterChain.doFilter(request, response)
            return
        }
        val ip = ClientIp.of(request)
        val session = request.getSession(false) ?: return deny(response)
        if (!AdminSession.hasIdentity(session)) return deny(response)
        if (AdminSession.boundIp(session) != ip) return deny(response)
        if (!allowlistService.isAllowed(ip)) return deny(response)
        // sliding refresh 를 제거했다(#669) — /admin 접근만으로 세션이 무한 연장되지 않게, 연장은 명시적 버튼으로만 한다.
        // dev 도메인 게이트(EnvironmentAccessFilter)는 개발·테스트 편의로 sliding 을 유지한다(관심사 분리).
        // 신원 확립 — Discord actor(표시명)를 이 요청에 싣는다. MDC 는 요청 내내 떠 있어 도메인 로그에 "누가"가 찍히고,
        // attribute 는 AccessLogFilter(바깥)가 access log 한 줄에 재주입한다(userId 와 동일 흐름). hasIdentity 가
        // non-blank 를 보장하나 타입상 nullable 이라 Elvis 로 방어한다(여기 닿으면 사실상 non-null).
        val actor = AdminSession.actorName(session) ?: return deny(response)
        MDC.put(LoggingKeys.ADMIN_ACTOR, actor)
        request.setAttribute(LoggingKeys.ADMIN_ACTOR, actor)
        try {
            filterChain.doFilter(request, response)
        } finally {
            // 이 요청 범위로만 MDC 를 빌렸다 돌려준다(스레드 재사용 시 누수 방지). attribute 는 같은 요청 객체라 자연 소멸.
            MDC.remove(LoggingKeys.ADMIN_ACTOR)
        }
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !isGatedRequest(request)

    // setStatus 로 막는다(sendError 금지) — sendError 는 /error 로 ERROR 디스패치를 일으켜 메인 체인이 401 로
    // 가로채면 "존재 숨김(404)" 의도가 깨진다. 체인을 더 진행하지 않으니 빈 404 로 응답이 닫힌다.
    private fun deny(response: HttpServletResponse) {
        response.status = HttpServletResponse.SC_NOT_FOUND
    }

    companion object {
        // 게이트 판정 경로를 Spring 라우팅과 동일하게 정규화한 뒤 매칭한다 — raw requestURI 로 판정하면
        // Spring 은 정규화 경로로 라우팅하는데 판정은 원문이라 불일치가 생겨, `/%61dmin/announcements`(percent-encoding)·
        // `/admin;x=1`(matrix param) 처럼 필터는 안 걸고 dispatcher 는 서빙하는 우회가 뚫린다. 그 경로로 들어오면
        // 세션·세션-IP 바인딩·allowlist 검사가 통째로 생략된 채 백오피스에 닿는다(#986).
        // EnvironmentAccessFilter 가 같은 이유로 쓰는 UrlPathHelper(removeSemicolonContent·urlDecode 기본 on)를 그대로 쓴다.
        private val PATH_HELPER = UrlPathHelper.defaultInstance

        fun isGatedRequest(request: HttpServletRequest): Boolean = isGatedPath(PATH_HELPER.getPathWithinApplication(request))

        // /admin 과 /admin/** 만 게이트. /admin-access 는 다른 prefix 라 제외(공개 진입).
        // 세그먼트 경계로 매칭해 /admin-access 류 과매칭을 막는다.
        fun isGatedPath(uri: String): Boolean = uri == "/admin" || uri.startsWith("/admin/")
    }
}
