package com.depromeet.piki.admin.access

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession

// 백오피스 접근 세션 — Discord grant 가 발급한 신원(Discord 표시명)과 바인딩된 IP 를 담는다.
// password·계정이 없으므로 "이 세션은 Discord 로 검증된 사용자 X" 가 곧 신원이다(감사·로그 actor). boundIp 로 세션-IP 를
// 묶어 쿠키가 탈취돼도 다른 IP 에선 못 쓰게 한다 — AdminAccessFilter 가 요청 IP == boundIp 를 확인한다.
//
// 세션은 Spring Session 을 통해 Redis 에 저장된다(#885, 배선은 build.gradle.kts) — 배포로 컨테이너가 바뀌어도 신원이 살아남는다.
// 담는 값은 String 으로만 유지한다: Spring Session 기본 JDK 직렬화로 저장되므로, 커스텀 타입을 담으면 무중단 배포 중
// 구·신버전이 같은 Redis 를 공유하는 동안 역직렬화가 깨질 수 있다(그때는 직렬화 호환성 테스트가 함께 필요하다).
object AdminSession {
    private const val ACTOR_NAME = "admin.actorName"
    private const val BOUND_IP = "admin.boundIp"

    // grant 토큰의 userId(claim "u")는 세션에 싣지 않는다 — 저장만 되고 읽는 곳이 없던 죽은 값이었다(#885).
    // 감사 actor 는 표시명이고, 토큰 발급 대상 식별은 토큰 서명 페이로드가 이미 들고 있다.
    fun establish(
        session: HttpSession,
        name: String,
        ip: String,
    ) {
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
