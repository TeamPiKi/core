package com.depromeet.piki.admin.access

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession

// 백오피스 접근 세션 — Discord grant 가 발급한 신원(Discord 표시명·유저 id)과 바인딩된 IP 를 담는다.
// password·계정이 없으므로 "이 세션은 Discord 로 검증된 사용자 X" 가 곧 신원이다(감사·로그 actor). boundIp 로 세션-IP 를
// 묶어 쿠키가 탈취돼도 다른 IP 에선 못 쓰게 한다 — AdminAccessFilter 가 요청 IP == boundIp 를 확인한다.
object AdminSession {
    private const val ACTOR_NAME = "admin.actorName"
    private const val ACTOR_USER_ID = "admin.actorUserId"
    private const val BOUND_IP = "admin.boundIp"

    fun establish(
        session: HttpSession,
        userId: String,
        name: String,
        ip: String,
    ) {
        session.setAttribute(ACTOR_USER_ID, userId)
        session.setAttribute(ACTOR_NAME, name)
        session.setAttribute(BOUND_IP, ip)
    }

    // 감사·로그에 찍히는 actor 이름 (= Discord 표시명).
    fun actorName(session: HttpSession): String? = session.getAttribute(ACTOR_NAME) as? String

    // 감사 actor 의 요청 단위 편의 진입점 — 게이트(#526·#654)가 세션에 바인딩한 신원을 읽고, 게이트를 우회하는
    // 로컬(admin.enabled)엔 세션이 없어 "운영자" 로 폴백한다. admin 컨트롤러들이 같은 한 줄을 복제하던 것을 모은다.
    fun actorName(request: HttpServletRequest): String = request.getSession(false)?.let { actorName(it) } ?: "운영자"

    fun boundIp(session: HttpSession): String? = session.getAttribute(BOUND_IP) as? String

    fun hasIdentity(session: HttpSession): Boolean = !actorName(session).isNullOrBlank()
}
