package com.depromeet.piki.auth.filter

import com.depromeet.piki.auth.infrastructure.jwt.JwtProperties
import com.depromeet.piki.auth.infrastructure.jwt.JwtProvider
import com.depromeet.piki.support.StubWithdrawnTokenStore
import com.depromeet.piki.user.domain.IdentityType
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Duration
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JwtAuthenticationFilterTest {
    private val jwtProvider =
        JwtProvider(
            JwtProperties(
                secret = TEST_SECRET,
                accessTokenExpiry = Duration.ofHours(1),
                refreshTokenExpiry = Duration.ofDays(14),
                refreshTokenGrace = Duration.ofSeconds(10),
            ),
        )
    private val withdrawnTokenStore = StubWithdrawnTokenStore()
    private val filter = JwtAuthenticationFilter(jwtProvider, withdrawnTokenStore)

    @AfterTest
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `유효한 access token 으로 호출하면 SecurityContext 에 userId 가 principal 로 박힌다`() {
        val userId = UUID.randomUUID()
        val token = jwtProvider.generateAccessToken(userId, IdentityType.GUEST)
        val request =
            MockHttpServletRequest().apply {
                addHeader(HttpHeaders.AUTHORIZATION, "Bearer $token")
            }

        filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

        val authentication = SecurityContextHolder.getContext().authentication
        assertEquals(userId, authentication?.principal)
        val authorities = authentication?.authorities?.map { it.authority }.orEmpty()
        assertTrue(IdentityType.GUEST.name in authorities)
    }

    @Test
    fun `탈퇴 회원의 access token 은 denylist 로 거부돼 SecurityContext 가 비어 있다`() {
        val userId = UUID.randomUUID()
        val token = jwtProvider.generateAccessToken(userId, IdentityType.MEMBER)
        withdrawnTokenStore.markWithdrawn(userId)
        val request =
            MockHttpServletRequest().apply {
                addHeader(HttpHeaders.AUTHORIZATION, "Bearer $token")
            }

        filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `Authorization 헤더가 없으면 SecurityContext 가 비어 있다`() {
        filter.doFilter(MockHttpServletRequest(), MockHttpServletResponse(), MockFilterChain())

        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `Bearer prefix 가 아닌 헤더면 SecurityContext 가 비어 있다`() {
        val request =
            MockHttpServletRequest().apply {
                addHeader(HttpHeaders.AUTHORIZATION, "Token abc123")
            }

        filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `위조된 토큰으로 호출하면 SecurityContext 가 비어 있다`() {
        val request =
            MockHttpServletRequest().apply {
                addHeader(HttpHeaders.AUTHORIZATION, "Bearer invalid.token.value")
            }

        filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `refresh token 으로 호출하면 type 불일치로 SecurityContext 가 비어 있다`() {
        val refreshToken = jwtProvider.generateRefreshToken(UUID.randomUUID(), jwtProvider.newSessionId())
        val request =
            MockHttpServletRequest().apply {
                addHeader(HttpHeaders.AUTHORIZATION, "Bearer $refreshToken")
            }

        filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `다른 secret 으로 서명된 토큰은 SecurityContext 가 비어 있다`() {
        val otherProvider =
            JwtProvider(
                JwtProperties(
                    secret = OTHER_SECRET,
                    accessTokenExpiry = Duration.ofHours(1),
                    refreshTokenExpiry = Duration.ofDays(14),
                refreshTokenGrace = Duration.ofSeconds(10),
                ),
            )
        val token = otherProvider.generateAccessToken(UUID.randomUUID(), IdentityType.GUEST)
        val request =
            MockHttpServletRequest().apply {
                addHeader(HttpHeaders.AUTHORIZATION, "Bearer $token")
            }

        filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())

        assertNull(SecurityContextHolder.getContext().authentication)
    }

    companion object {
        private const val TEST_SECRET = "test-secret-key-must-be-at-least-32-chars!"
        private const val OTHER_SECRET = "other-secret-key-must-be-at-least-32-chars!"
    }
}
