package com.depromeet.piki.admin.access

import com.depromeet.piki.admin.config.AdminProperties
import com.depromeet.piki.support.IntegrationTestSupport
import jakarta.servlet.Filter
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.session.Session
import org.springframework.session.SessionRepository
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

// 백오피스 세션이 프로세스 밖(Redis)에 저장되는지 검증한다(#885).
//
// 이 배선이 빠져도 로컬·CI 는 아무 증상이 없다 — 한 프로세스만 도는 동안엔 인메모리 세션도 똑같이 동작하기
// 때문이다. 증상은 배포(blue-green 컨테이너 교체) 뒤에야 "관리자가 갑자기 404 를 본다"로 드러나고, 그건
// 테스트가 아니라 운영에서 발견된다. 그래서 grant 가 확립한 세션을 응답 쿠키만 들고 세션 저장소에서 다시
// 찾는다 — 교체된 새 컨테이너가 그 쿠키를 받았을 때 하는 일과 같다.
@Transactional
class AdminSessionPersistenceIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var allowlistService: AdminAllowlistService

    @Autowired
    private lateinit var adminProperties: AdminProperties

    @Test
    fun `grant 링크로 확립한 백오피스 세션은 응답 쿠키만으로 세션 저장소에서 다시 찾힌다`() {
        // Redis 키(allowlist·세션)는 트랜잭션 롤백 대상이 아니라 남으므로 다른 테스트와 겹치지 않는 IP 를 쓴다.
        val ip = "203.0.113.20"
        val token =
            allowlistService.issueGrantToken(
                userId = "discord-user-885",
                name = "테스트운영자",
                env = adminProperties.environment,
                dest = GrantDest.ADMIN,
            )

        val response =
            sessionMockMvc()
                .perform(get("/admin-access/grant").param("token", token).with(from(realIp = ip)))
                .andExpect(status().is3xxRedirection)
                .andExpect(redirectedUrl("/admin"))
                .andReturn()
                .response

        // 브라우저가 받아 가는 건 이 쿠키 하나뿐이다. 새 컨테이너도 이것만 들고 신원을 복원할 수 있어야 한다.
        val cookie = response.getCookie(SESSION_COOKIE)
        val stored = storedSession(sessionIdOf(cookie?.value))
        assertNotNull(stored, "세션이 저장소에서 조회되지 않았다 — 인메모리 세션이면 프로세스가 바뀔 때 사라진다")
        assertEquals("테스트운영자", stored.getAttribute<String>("admin.actorName"))
        assertEquals(ip, stored.getAttribute<String>("admin.boundIp"))
    }

    @Test
    fun `백오피스 세션 쿠키는 Secure 와 HttpOnly 를 달고 나간다`() {
        // 세션 쿠키는 톰캣·Spring Session 이 발급해 TokenCookieWriter(JWT 쿠키 정책)를 안 거친다. 그래서 Secure 가
        // 설정으로만 붙는데, 그 설정이 실제 Set-Cookie 까지 도달하는지는 아무 곳에서도 드러나지 않는다 — 값이 안
        // 먹어도 부팅·기능은 멀쩡하고 운영에서 평문 전송으로만 나타난다. 세션이 24h 를 사는 지금 그 창을 열어둘 수 없다.
        val token =
            allowlistService.issueGrantToken(
                userId = "discord-user-885-cookie",
                name = "테스트운영자",
                env = adminProperties.environment,
                dest = GrantDest.ADMIN,
            )

        val cookie =
            sessionMockMvc()
                .perform(get("/admin-access/grant").param("token", token).with(from(realIp = "203.0.113.21")))
                .andExpect(status().is3xxRedirection)
                .andReturn()
                .response
                .getCookie(SESSION_COOKIE)

        assertNotNull(cookie, "$SESSION_COOKIE 쿠키가 없다")
        assertEquals(true, cookie.secure, "세션 쿠키에 Secure 가 없다 — 평문 구간에서 쿠키가 새어 나간다")
        assertEquals(true, cookie.isHttpOnly, "세션 쿠키에 HttpOnly 가 없다 — 스크립트가 세션을 읽을 수 있다")
    }

    // 세션 저장소 필터를 명시적으로 끼운다 — webAppContextSetup 은 서블릿 컨테이너의 필터 등록을 재현하지 않아,
    // 이게 없으면 요청이 MockHttpSession 을 써 Redis 를 타지 않는다(EnvironmentGateIntegrationTest 와 같은 방식).
    private fun sessionMockMvc(): MockMvc =
        MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .addFilters<DefaultMockMvcBuilder>(
                webApplicationContext.getBean(SESSION_FILTER_BEAN, Filter::class.java),
            ).build()

    // 세션 저장소를 타입으로만 찾는다 — 구현마다 제네릭 인자가 달라(RedisSessionRepository 는 RedisSession)
    // SessionRepository<out Session> 필드 주입은 매칭되지 않는다.
    private fun storedSession(id: String): Session? = webApplicationContext.getBean(SessionRepository::class.java).findById(id)

    // Spring Session 쿠키는 세션 id 를 base64 로 실어 보낸다(DefaultCookieSerializer 기본).
    private fun sessionIdOf(cookieValue: String?): String {
        val value = cookieValue ?: error("$SESSION_COOKIE 쿠키가 없다 — 세션 저장소 필터가 배선되지 않았다")
        return String(Base64.getDecoder().decode(value))
    }

    // MockMvc 기본 remoteAddr(127.0.0.1)은 ClientIp 가 신뢰하는 프록시라 X-Real-IP 가 채택된다 — nginx 를 거친 요청의 모양.
    private fun from(realIp: String) =
        { request: MockHttpServletRequest ->
            request.addHeader("X-Real-IP", realIp)
            request
        }

    companion object {
        // Spring Session 이 발급하는 쿠키명·필터 빈 이름의 기본값.
        private const val SESSION_COOKIE = "SESSION"
        private const val SESSION_FILTER_BEAN = "springSessionRepositoryFilter"
    }
}
