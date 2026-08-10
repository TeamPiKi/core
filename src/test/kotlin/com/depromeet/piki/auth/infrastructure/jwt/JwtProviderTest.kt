package com.depromeet.piki.auth.infrastructure.jwt

import com.depromeet.piki.user.domain.IdentityType
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JwtProviderTest {
    private val properties =
        JwtProperties(
            secret = TEST_SECRET,
            accessTokenExpiry = Duration.ofHours(1),
            refreshTokenExpiry = Duration.ofDays(14),
            refreshTokenGrace = Duration.ofSeconds(10),
        )
    private val jwtProvider = JwtProvider(properties)

    @Test
    fun `access token 을 발급하면 동일한 userId 와 identityType 을 parseAccessToken 으로 추출할 수 있다`() {
        val userId = UUID.randomUUID()
        val token = jwtProvider.generateAccessToken(userId, IdentityType.MEMBER)
        val payload = jwtProvider.parseAccessToken(token)
        assertEquals(userId, payload?.userId)
        assertEquals(IdentityType.MEMBER, payload?.identityType)
    }

    @Test
    fun `refresh token 을 발급하면 동일한 userId 와 sessionId 를 parseRefreshToken 으로 추출할 수 있다`() {
        val userId = UUID.randomUUID()
        val sessionId = jwtProvider.newSessionId()
        val token = jwtProvider.generateRefreshToken(userId, sessionId)
        val payload = jwtProvider.parseRefreshToken(token)
        assertEquals(userId, payload?.userId)
        assertEquals(sessionId, payload?.sessionId)
    }

    // 회전이 세션을 이어가려면 같은 sid 를 넘겼을 때 토큰만 새로 나와야 한다. jti 를 세션 축으로 쓰면
    // 회전마다 값이 바뀌어 Redis 슬롯이 갈리는데, 그 실수를 여기서 잡는다(#893).
    @Test
    fun `같은 sessionId 로 다시 발급하면 토큰은 달라도 sessionId 는 유지된다`() {
        val userId = UUID.randomUUID()
        val sessionId = jwtProvider.newSessionId()
        val first = jwtProvider.generateRefreshToken(userId, sessionId)
        val second = jwtProvider.generateRefreshToken(userId, sessionId)
        assertNotEquals(first, second)
        assertEquals(sessionId, jwtProvider.parseRefreshToken(second)?.sessionId)
    }

    @Test
    fun `로그인마다 새로 발급한 sessionId 는 서로 다르다`() {
        assertNotEquals(jwtProvider.newSessionId(), jwtProvider.newSessionId())
    }

    @Test
    fun `refresh token 을 parseAccessToken 으로 호출하면 type 불일치로 null 을 반환한다`() {
        val token = jwtProvider.generateRefreshToken(UUID.randomUUID(), jwtProvider.newSessionId())
        assertNull(jwtProvider.parseAccessToken(token))
    }

    @Test
    fun `access token 을 parseRefreshToken 으로 호출하면 type 불일치로 null 을 반환한다`() {
        val token = jwtProvider.generateAccessToken(UUID.randomUUID(), IdentityType.GUEST)
        assertNull(jwtProvider.parseRefreshToken(token))
    }

    @Test
    fun `위조된 토큰은 null 을 반환한다`() {
        assertNull(jwtProvider.parseAccessToken("invalid.token.value"))
    }

    @Test
    fun `만료된 토큰은 null 을 반환한다`() {
        val expiredProvider =
            JwtProvider(
                JwtProperties(
                    secret = TEST_SECRET,
                    accessTokenExpiry = Duration.ofSeconds(-1),
                    refreshTokenExpiry = Duration.ofSeconds(-1),
                    refreshTokenGrace = Duration.ofSeconds(10),
                ),
            )
        val token = expiredProvider.generateAccessToken(UUID.randomUUID(), IdentityType.GUEST)
        assertNull(jwtProvider.parseAccessToken(token))
    }

    @Test
    fun `서로 다른 userId 로 발급한 토큰에서 추출한 userId 가 각각 일치한다`() {
        val userId1 = UUID.randomUUID()
        val userId2 = UUID.randomUUID()
        val token1 = jwtProvider.generateAccessToken(userId1, IdentityType.GUEST)
        val token2 = jwtProvider.generateAccessToken(userId2, IdentityType.MEMBER)
        assertEquals(userId1, jwtProvider.parseAccessToken(token1)?.userId)
        assertEquals(userId2, jwtProvider.parseAccessToken(token2)?.userId)
    }

    @Test
    fun `다른 secret 으로 서명한 토큰은 null 을 반환한다`() {
        val otherProvider =
            JwtProvider(
                JwtProperties(
                    secret = "other-secret-key-must-be-at-least-32-chars!",
                    accessTokenExpiry = Duration.ofHours(1),
                    refreshTokenExpiry = Duration.ofDays(14),
            refreshTokenGrace = Duration.ofSeconds(10),
                ),
            )
        val token = otherProvider.generateAccessToken(UUID.randomUUID(), IdentityType.GUEST)
        assertNotNull(otherProvider.parseAccessToken(token))
        assertNull(jwtProvider.parseAccessToken(token))
    }

    companion object {
        private const val TEST_SECRET = "test-secret-key-must-be-at-least-32-chars!"
    }
}
