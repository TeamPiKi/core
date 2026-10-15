package com.depromeet.piki.auth.infrastructure.oauth.apple

import com.depromeet.piki.auth.infrastructure.oauth.OAuthClient
import com.depromeet.piki.auth.infrastructure.oauth.OAuthException
import com.depromeet.piki.auth.infrastructure.oauth.OAuthParams
import com.depromeet.piki.auth.infrastructure.oauth.OAuthProvider
import com.depromeet.piki.auth.infrastructure.oauth.OAuthRestClient
import com.depromeet.piki.auth.infrastructure.oauth.OAuthUserInfo
import com.depromeet.piki.auth.infrastructure.oauth.apple.dto.AppleTokenResponse
import com.depromeet.piki.auth.infrastructure.oauth.logOAuthProviderError
import io.jsonwebtoken.Claims
import io.jsonwebtoken.Header
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.Locator
import io.jsonwebtoken.security.Jwks
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.body
import org.springframework.web.util.UriComponentsBuilder
import java.security.Key
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date

class AppleOAuthClient(
    private val props: AppleProperties,
    observationRegistry: ObservationRegistry,
) : OAuthClient,
    AppleNotificationVerifier {
    override val provider = OAuthProvider.APPLE

    private val log = LoggerFactory.getLogger(javaClass)
    private val appleClient = OAuthRestClient.create(APPLE_BASE_URL, observationRegistry)

    @Volatile private var cachedJwksJson: String? = null
    @Volatile private var jwksCachedAt = 0L

    override fun buildAuthUrl(
        state: String,
        redirectUri: String?,
    ): String =
        UriComponentsBuilder
            .fromUriString(AUTH_URL)
            .queryParam(OAuthParams.CLIENT_ID, props.clientId)
            .queryParam(OAuthParams.REDIRECT_URI, redirectUri ?: props.redirectUri)
            .queryParam(OAuthParams.RESPONSE_TYPE, OAuthParams.RESPONSE_TYPE_CODE)
            .queryParam("response_mode", "form_post")
            .queryParam(OAuthParams.SCOPE, "email name")
            .queryParam(OAuthParams.STATE, state)
            .build()
            .encode()
            .toUriString()

    // v1: 백엔드가 code → id_token 교환 후 id_token 검증. aud = clientId (Services ID).
    override fun fetchUserInfoByCode(
        code: String,
        redirectUri: String,
    ): OAuthUserInfo {
        val clientSecret = buildClientSecret()
        val params =
            LinkedMultiValueMap<String, String>().apply {
                add(OAuthParams.GRANT_TYPE, OAuthParams.GRANT_TYPE_AUTHORIZATION_CODE)
                add(OAuthParams.CLIENT_ID, props.clientId)
                add(OAuthParams.CLIENT_SECRET, clientSecret)
                add(OAuthParams.REDIRECT_URI, redirectUri)
                add(OAuthParams.CODE, code)
            }
        val tokenResponse =
            try {
                appleClient
                    .post()
                    .uri(TOKEN_PATH)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(params)
                    .retrieve()
                    .body<AppleTokenResponse>()
                    ?: error("Apple token response body is null")
            } catch (e: RestClientResponseException) {
                // token 교환 HTTP 에러 응답은 시맨틱 기반으로 분류한다 (invalid_grant→400 · 설정오류→502/SERVER_ERROR · 그 외→502).
                val exception = AppleOAuthErrorClassifier.classify(e.statusCode, e.responseBodyAsString, e)
                logOAuthProviderError(log, "Apple", "token", e.statusCode.value(), e.responseBodyAsString, exception.category)
                throw exception
            } catch (e: Exception) {
                throw OAuthException.providerError(e)
            }
        return verifyIdTokenAndExtract(tokenResponse.idToken, expectedAud = props.clientId)
    }

    // v2: iOS SDK 가 발급한 identityToken(JWT)을 직접 검증. aud = bundleId (Bundle ID).
    override fun fetchUserInfoByAccessToken(accessToken: String): OAuthUserInfo =
        verifyIdTokenAndExtract(accessToken, expectedAud = props.bundleId)

    // Apple 서버-서버 알림 검증. id_token 과 같은 JWKS·issuer·kid 회전 인프라(parseWithKidRotationRetry)를
    // 재사용해 JWKS 캐시를 한 인스턴스에서 공유한다. 알림 JWT 는 aud 가 우리 client_id(Services ID) 또는
    // bundleId 이고, payload 의 events 클레임을 들고 온다. 검증·파싱 실패는 모두 AppleNotificationException(401)로
    // 변환한다 — 이 엔드포인트는 permitAll 이라 서명이 유일한 진위 방어선이고, 위조/비정상 호출은 401 로 거부한다.
    override fun verify(payloadJwt: String): AppleNotificationEvent = toNotificationEvent(parseNotificationClaims(payloadJwt))

    // 알림 JWT 파싱. id_token 경로(parseWithKidRotationRetry)와 같은 JWKS·kid 회전 로직을 따르되 예외 매핑이 다르다 —
    // JWKS fetch 실패(외부 의존성)는 providerError(502)로, 서명·claims 파싱 실패(위조)는 invalidSignature(401)로 분기한다.
    // parseWithKidRotationRetry 를 그대로 쓰면 둘 다 OAuthException.providerError 로 뭉쳐 구분이 불가능해 따로 둔다.
    private fun parseNotificationClaims(payloadJwt: String): Claims {
        val jwks = fetchJwksForNotification()
        return try {
            parseIdToken(payloadJwt, jwks)
        } catch (e: Exception) {
            // 캐시 JWKS 에 없는 kid = Apple 키 회전 가능성 → 캐시 무효화 후 1회 재시도. 그 외(서명 위조·exp·issuer)는 401.
            if (!isKidRotation(e)) throw AppleNotificationException.invalidSignature(e)
            jwksCachedAt = 0L
            val refreshed = fetchJwksForNotification()
            try {
                parseIdToken(payloadJwt, refreshed)
            } catch (retry: Exception) {
                throw AppleNotificationException.invalidSignature(retry)
            }
        }
    }

    // JWKS 조회는 외부 호출이므로 실패 시 warn 으로 남긴다(CLAUDE.md: 외부 호출 실패 = warn). providerError 는
    // GlobalExceptionHandler 에서 info 로만 요약되므로, 외부 의존성 장애를 운영에서 놓치지 않게 호출 지점에 warn + 스택을 남긴다.
    private fun fetchJwksForNotification(): String =
        try {
            getJwks()
        } catch (e: Exception) {
            log.warn("Apple JWKS 조회 실패로 알림 검증 불가 (재시도 가능)", e)
            throw AppleNotificationException.providerError(e)
        }

    // 서명·issuer 검증이 끝난 claims 에서 aud 를 확인하고 events 를 파싱한다. 외부 호출(JWKS) 없는 순수 검증이라
    // 단위 테스트가 직접 호출한다 — aud 위조·events 누락 같은 보안 경로를 stub 없이 망라한다.
    // aud 는 우리 client_id(Services ID) 또는 bundleId 여야 한다. 불일치·형식 오류는 AppleNotificationException(401).
    internal fun toNotificationEvent(claims: Claims): AppleNotificationEvent {
        val aud = claims.audience ?: emptySet()
        if (aud.none { it == props.clientId || it == props.bundleId }) {
            log.warn("Apple 알림 aud 불일치: actual={}", aud)
            throw AppleNotificationException.invalidSignature()
        }
        val eventsJson = claims["events"] as? String ?: throw AppleNotificationException.invalidSignature()
        return try {
            AppleNotificationEvent.parse(eventsJson)
        } catch (e: Exception) {
            throw AppleNotificationException.invalidSignature(e)
        }
    }

    private fun verifyIdTokenAndExtract(
        idToken: String,
        expectedAud: String,
    ): OAuthUserInfo {
        val claims = parseWithKidRotationRetry(idToken)
        return verifyClaimsAndExtract(claims, expectedAud)
    }

    // JWKS refresh+재시도는 "kid 가 현재 캐시된 JWKS 에 없음"(= Apple 키 회전) 일 때만 한다.
    // 서명 위조(kid 는 맞는데 검증 실패)·exp·issuer·malformed 는 refresh 로 복구되지 않으므로 즉시 실패시킨다 —
    // 이렇게 좁히지 않으면 만료·위조 토큰이 몰릴 때 매 실패마다 /auth/keys 를 다시 호출해 self-amplifying 으로 증폭된다.
    private fun parseWithKidRotationRetry(idToken: String): Claims =
        try {
            parseIdToken(idToken, getJwks())
        } catch (e: Exception) {
            if (!isKidRotation(e)) throw OAuthException.providerError(e)
            // 캐시 JWKS 에 없는 kid → Apple 이 키를 회전했을 가능성. 캐시 무효화 후 최신 JWKS 로 1회만 재시도.
            // 운영 추적용으로 exception class·message 를 남긴다.
            log.warn(
                "Apple id_token 의 kid 가 캐시 JWKS 에 없음, 키 회전 가능성 → 캐시 무효화 후 재시도: {}: {}",
                e.javaClass.simpleName,
                e.message,
            )
            jwksCachedAt = 0L
            try {
                parseIdToken(idToken, getJwks())
            } catch (retry: Exception) {
                throw OAuthException.providerError(retry)
            }
        }

    // JJWT 가 keyLocator 의 예외를 wrap 할 수 있어 cause 체인까지 본다. AppleKidNotFoundException 이
    // 체인 어딘가에 있으면 "kid 회전" 으로 보고 JWKS 재조회를 1회 허용한다.
    internal fun isKidRotation(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { it is AppleKidNotFoundException }

    // JWKS fetch / parse 와 분리된 순수 클레임 검증. 외부 호출 없이 단위 테스트가 직접 호출한다.
    // parse 가 끝난 claims 를 받아 aud · sub 만 본다 — 검증 실패는 JWKS refresh 로 복구되지 않으므로
    // 재시도 경로에 포함시키지 않는다.
    internal fun verifyClaimsAndExtract(
        claims: Claims,
        expectedAud: String,
    ): OAuthUserInfo {
        // aud 는 string 또는 array — JJWT 가 Set<String> 으로 정규화
        val aud = claims.audience ?: emptySet()
        if (!aud.contains(expectedAud)) {
            log.warn("Apple id_token aud 불일치: expected={}, actual={}", expectedAud, aud)
            throw OAuthException.providerError(IllegalArgumentException("Apple id_token audience mismatch"))
        }

        // sub 누락은 Apple 의 비정상 응답·공격성 JWT 가능성. 외부 입력 검증 실패이므로 500 이 아닌
        // OAuthException.providerError(502) 로 매핑해 contract 일관성을 유지한다.
        val sub =
            claims.subject
                ?: throw OAuthException.providerError(IllegalArgumentException("Apple id_token sub missing"))
        // email 클레임은 최초 동의 시에만 들어온다(2회차·미동의는 부재). Private Relay 면 relay 주소. 없으면 null.
        val email = (claims["email"] as? String)?.ifBlank { null }
        return OAuthUserInfo(provider = OAuthProvider.APPLE, socialId = sub, profileImage = null, email = email)
    }


    internal fun parseIdToken(
        idToken: String,
        jwksJson: String,
    ): Claims {
        val jwkSet = Jwks.setParser().build().parse(jwksJson)
        // Header extends Map<String, Object> — header["kid"] 으로 kid 값에 직접 접근한다.
        // jwkSet.getKeys() 는 JWK 셋의 키 목록 (Map.keys 와 다름 — Map.keys 는 문자열 키 셋).
        val keyLocator =
            object : Locator<Key> {
                // kid 부재·JWKS 미존재는 "Apple 키 회전" 신호로 구분해 던진다. verifyIdTokenAndExtract 가
                // 이 예외(또는 cause 체인)일 때만 JWKS 를 다시 받아 1회 재시도한다 — 서명 위조·exp·issuer
                // 실패는 refresh 로 복구되지 않으므로 재시도 대상이 아니다.
                override fun locate(header: Header): Key? {
                    val kid = header["kid"] as? String ?: throw AppleKidNotFoundException(null)
                    return jwkSet.getKeys().firstOrNull { it.getId() == kid }?.toKey()
                        ?: throw AppleKidNotFoundException(kid)
                }
            }
        return Jwts
            .parser()
            .keyLocator(keyLocator)
            .requireIssuer(APPLE_ISSUER)
            .build()
            .parseSignedClaims(idToken)
            .payload
    }

    private fun getJwks(): String {
        val now = System.currentTimeMillis()
        cachedJwksJson?.let { cached ->
            if (now - jwksCachedAt < JWKS_CACHE_TTL_MS) return cached
        }

        return try {
            val fresh =
                appleClient
                    .get()
                    .uri(JWKS_PATH)
                    .retrieve()
                    .body(String::class.java)
                    ?: error("Apple JWKS response body is null")
            cachedJwksJson = fresh
            jwksCachedAt = System.currentTimeMillis()
            fresh
        } catch (e: Exception) {
            throw OAuthException.providerError(e)
        }
    }

    internal fun buildClientSecret(): String {
        val privateKey = parseEcPrivateKey(props.privateKey)
        val now = Date()
        val expiry = Date(now.time + CLIENT_SECRET_TTL_MS)
        return Jwts
            .builder()
            .header()
            .add("kid", props.keyId)
            .and()
            .issuer(props.teamId)
            .subject(props.clientId)
            .audience()
            .add(APPLE_ISSUER)
            .and()
            .issuedAt(now)
            .expiration(expiry)
            .signWith(privateKey)
            .compact()
    }

    internal fun parseEcPrivateKey(pem: String): java.security.PrivateKey {
        val stripped =
            pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\n", "\n")
                .replace("\n", "")
                .trim()
        val keyBytes = Base64.getDecoder().decode(stripped)
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
    }

    companion object {
        private const val APPLE_BASE_URL = "https://appleid.apple.com"
        private const val AUTH_URL = "https://appleid.apple.com/auth/authorize"
        private const val TOKEN_PATH = "/auth/token"
        private const val JWKS_PATH = "/auth/keys"
        private const val APPLE_ISSUER = "https://appleid.apple.com"
        private val JWKS_CACHE_TTL_MS = 60 * 60 * 1000L // 1시간
        private val CLIENT_SECRET_TTL_MS = 180L * 24 * 60 * 60 * 1000 // 6개월 (Apple 최대)
    }
}

// id_token 의 kid 가 현재 JWKS 에 없을 때 keyLocator 가 던진다 = Apple 키 회전 신호.
// 이 예외(또는 cause 체인 포함)일 때만 JWKS 캐시를 무효화하고 재조회한다 (parseWithKidRotationRetry).
private class AppleKidNotFoundException(
    kid: String?,
) : RuntimeException("Apple JWKS 에서 kid=$kid 를 찾지 못했다")
