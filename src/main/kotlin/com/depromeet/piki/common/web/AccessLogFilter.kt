package com.depromeet.piki.common.web

import com.depromeet.piki.common.logging.LoggingKeys
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

// 모든 API 요청의 처리 결과를 한 줄로 남기는 access log — method·path·status·latency. 성공(2xx)·실패(4xx/5xx)를
// 도메인 로깅 유무와 무관하게 균일하게 남겨 "이 API 가 무슨 status 로 얼마나 걸려 끝났나"를 빠짐없이 본다.
// 실패 사유의 디테일(예외 클래스·메시지)은 GlobalExceptionHandler 가 담당하고, 여기선 결과 status 만 균일하게 찍는다.
// path 만 남기고 쿼리스트링은 싣지 않는다 — 쿼리에 토큰이 실릴 수 있다(CLAUDE.md 민감정보 마스킹).
//
// order: 관측(observation, HIGHEST+1)·TraceIdHeaderFilter(+2) 안쪽(+3)이라 traceId 가 MDC 에 차 있고,
// Security 필터(DEFAULT_FILTER_ORDER=-100)보다 바깥이라 인증 거부(401/403)로 컨트롤러에 닿기 전 끝난 요청도 잡는다.
//
// userId: 이 필터 finally 시점엔 JwtAuthenticationFilter(더 안쪽)가 자기 finally 에서 MDC userId 를 이미 지운 뒤다.
// 그래서 JwtAuthenticationFilter 가 함께 심어둔 request attribute 에서 읽어, 이 로그 한 줄 동안만 MDC 에 얹는다 —
// 콘솔 패턴 [user=...]·ECS userId 필드가 access log 에도 도메인 로그와 똑같이 찍힌다.
// adminActor(슬랙명)도 동일 패턴 — AdminAccessFilter(더 안쪽)가 자기 finally 에서 MDC 를 지우므로, 함께 심어둔
// attribute 에서 읽어 이 access log 한 줄에 재주입한다. /admin 요청은 "누가" 가 access·도메인 로그 양쪽에 찍힌다.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
class AccessLogFilter : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        // latency 는 monotonic clock(nanoTime)으로 잰다 — currentTimeMillis 는 NTP 시간 보정 때 음수·과대값으로
        // 깨질 수 있어 관측 지표로 부적합하다. wall-clock 표시 시각은 로그 패턴의 %d 가 따로 담당한다.
        val startNanos = System.nanoTime()
        try {
            filterChain.doFilter(request, response)
        } finally {
            val latencyMs = (System.nanoTime() - startNanos) / 1_000_000
            (request.getAttribute(LoggingKeys.USER_ID) as? String)?.let { MDC.put(LoggingKeys.USER_ID, it) }
            (request.getAttribute(LoggingKeys.ADMIN_ACTOR) as? String)?.let { MDC.put(LoggingKeys.ADMIN_ACTOR, it) }
            try {
                log.info("{} {} -> {} ({}ms)", request.method, request.requestURI, response.status, latencyMs)
            } finally {
                // 미인증·비-admin 요청이면 위에서 put 을 안 했어도 remove 는 무해(no-op). 이 한 줄 로그 범위로만 MDC 를 빌렸다 돌려준다.
                MDC.remove(LoggingKeys.USER_ID)
                MDC.remove(LoggingKeys.ADMIN_ACTOR)
            }
        }
    }

    // SSE 구독은 장시간 연결이라 latency 가 무의미하고, 하트비트는 연결 수에 비례해 쌓이며, actuator 는 scrape 노이즈다.
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val uri = request.requestURI
        return uri.startsWith("/actuator") ||
            uri == "/api/v1/notifications/subscribe" ||
            uri == "/api/v1/notifications/heartbeat"
    }
}
