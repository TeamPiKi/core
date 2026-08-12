package com.depromeet.piki.auth.infrastructure.jwt

import com.depromeet.piki.user.domain.IdentityType
import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey

private const val CLAIM_TYPE = "type"
private const val CLAIM_ROLE = "role"

// 로그인 세션 식별자(#893). refresh 토큰에만 실린다.
// jti 와 구분할 것 — jti 는 토큰마다 새로 생겨 회전할 때마다 바뀌므로 세션을 가리킬 수 없다.
// sid 는 로그인 때 한 번 만들어 회전 시 그대로 복사되며, Redis 키를 기기별로 가르는 축이다.
private const val CLAIM_SESSION_ID = "sid"
private val logger = LoggerFactory.getLogger(JwtProvider::class.java)

@Component
class JwtProvider(
    private val jwtProperties: JwtProperties,
) {
    // 부팅 시점에 즉시 생성한다. lazy 로 두면 첫 토큰 발급/검증 트래픽까지 키 유효성 문제가
    // 가려져 운영 사고의 표면이 트래픽 시점으로 미뤄진다. JwtProperties 의 @Size(min=32) 와
    // 합쳐 부팅 시점 fail-fast 보장.
    private val secretKey: SecretKey =
        Keys.hmacShaKeyFor(jwtProperties.secret.toByteArray(Charsets.UTF_8))

    fun generateAccessToken(
        userId: UUID,
        identityType: IdentityType,
    ): String =
        buildToken(
            userId = userId,
            type = TokenType.ACCESS,
            expiry = jwtProperties.accessTokenExpiry,
            role = identityType.name,
        )

    // sessionId 는 로그인 때 발급하고 회전 시 같은 값을 넘겨 유지한다 — 그래야 회전을 거쳐도 세션이 이어진다.
    fun generateRefreshToken(
        userId: UUID,
        sessionId: String,
    ): String =
        buildToken(
            userId = userId,
            type = TokenType.REFRESH,
            expiry = jwtProperties.refreshTokenExpiry,
            role = null,
            sessionId = sessionId,
        )

    fun newSessionId(): String = UUID.randomUUID().toString()

    fun parseAccessToken(token: String): AccessTokenPayload? =
        runCatching {
            val claims = parseClaims(token)
            val rawType = claims[CLAIM_TYPE] as? String
            val actualType = TokenType.fromClaim(rawType)
            check(actualType == TokenType.ACCESS) {
                "JWT type mismatch: expected=access, actual=${rawType ?: "<missing>"}"
            }
            val rawRole = claims[CLAIM_ROLE] as? String ?: error("role claim missing in access token")
            AccessTokenPayload(
                userId = UUID.fromString(claims.subject),
                identityType = IdentityType.valueOf(rawRole),
            )
        }.onFailure { logger.info("ACCESS JWT 파싱 실패: {}", it.message) }
            .getOrNull()

    // sessionId 는 nullable — #893 이전에 발급된 토큰에는 sid 클레임이 없다(refresh TTL 만큼 최대 14일 잔존).
    // 여기서 거부하지 않고 그대로 올려, 호출자가 "레거시 토큰" 을 별도 사유로 로깅·처리하게 한다.
    fun parseRefreshToken(token: String): RefreshTokenPayload? =
        runCatching {
            val claims = parseClaims(token)
            val rawType = claims[CLAIM_TYPE] as? String
            val actualType = TokenType.fromClaim(rawType)
            check(actualType == TokenType.REFRESH) {
                "JWT type mismatch: expected=refresh, actual=${rawType ?: "<missing>"}"
            }
            RefreshTokenPayload(
                userId = UUID.fromString(claims.subject),
                sessionId = (claims[CLAIM_SESSION_ID] as? String)?.ifBlank { null },
            )
        }.onFailure { logger.info("REFRESH JWT 파싱 실패: {}", it.message) }
            .getOrNull()

    fun validateToken(token: String): Boolean = runCatching { parseClaims(token) }.isSuccess

    private fun buildToken(
        userId: UUID,
        type: TokenType,
        expiry: Duration,
        role: String?,
        sessionId: String? = null,
    ): String {
        val now = Date()
        return Jwts
            .builder()
            .id(UUID.randomUUID().toString())
            .subject(userId.toString())
            .claim(CLAIM_TYPE, type.claimValue)
            .issuedAt(now)
            .expiration(Date(now.time + expiry.toMillis()))
            .apply { role?.let { claim(CLAIM_ROLE, it) } }
            .apply { sessionId?.let { claim(CLAIM_SESSION_ID, it) } }
            .signWith(secretKey)
            .compact()
    }

    private fun parseClaims(token: String): Claims =
        Jwts
            .parser()
            .verifyWith(secretKey)
            .build()
            .parseSignedClaims(token)
            .payload

    data class AccessTokenPayload(
        val userId: UUID,
        val identityType: IdentityType,
    )

    // sessionId 가 null 이면 #893 이전 발급 토큰이다 — 세션 슬롯을 특정할 수 없어 갱신을 거부한다(재로그인 유도).
    data class RefreshTokenPayload(
        val userId: UUID,
        val sessionId: String?,
    )
}
