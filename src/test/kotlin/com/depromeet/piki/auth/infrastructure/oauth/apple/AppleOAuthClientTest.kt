package com.depromeet.piki.auth.infrastructure.oauth.apple

import com.depromeet.piki.auth.infrastructure.oauth.OAuthException
import com.depromeet.piki.auth.infrastructure.oauth.OAuthProvider
import com.depromeet.piki.auth.infrastructure.oauth.OAuthUserInfo
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Jwks
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppleOAuthClientTest {
    // 단위 테스트에서 동적으로 만드는 EC 키쌍. Apple 의 .p8 / JWKS 를 흉내 내어
    // client_secret 서명 · id_token 서명/검증 분기를 모두 외부 호출 없이 검증한다.
    private val testKeyPair: KeyPair = generateEcKeyPair()
    private val testKid = "TEST_KID"
    private val testJwksJson = buildJwksJson(testKeyPair.public as ECPublicKey, testKid)
    private val testP8Pem = encodeP8Pem(testKeyPair.private as ECPrivateKey)

    private fun props(
        teamId: String = "TEST_TEAM_ID",
        keyId: String = testKid,
        clientId: String = "com.test.service",
        bundleId: String = "com.test.app",
        privateKey: String = testP8Pem,
        redirectUri: String = "https://example.com/callback",
        webCallbackUrl: String = "https://example.com/auth/callback/apple",
    ) = AppleProperties(
        teamId = teamId,
        keyId = keyId,
        clientId = clientId,
        bundleId = bundleId,
        privateKey = privateKey,
        redirectUri = redirectUri,
        webCallbackUrl = webCallbackUrl,
    )

    @Nested
    inner class PropertiesToString {
        @Test
        fun `toString 은 privateKey 를 마스킹한다 - 부팅 로그·BindException 으로 누출 방지`() {
            val p = props(privateKey = "-----BEGIN PRIVATE KEY-----\nSUPER_SECRET\n-----END PRIVATE KEY-----")
            val str = p.toString()
            assertTrue("***MASKED***" in str, "privateKey 자리에 마스킹 마커가 있어야 한다: $str")
            assertTrue("SUPER_SECRET" !in str, "privateKey 평문이 toString 에 노출되면 안 된다: $str")
            assertTrue("BEGIN PRIVATE KEY" !in str, "PEM 헤더도 노출되면 안 된다: $str")
        }
    }

    @Nested
    inner class ParseEcPrivateKey {
        @Test
        fun `정상 PEM 을 EC PrivateKey 로 파싱한다`() {
            val client = AppleOAuthClient(props(), ObservationRegistry.NOOP)
            val key = client.parseEcPrivateKey(testP8Pem)
            assertEquals("EC", key.algorithm)
        }

        @Test
        fun `환경변수 한 줄 형태 - 리터럴 백슬래시 n 을 실제 개행으로 치환해 파싱한다`() {
            val flattened = testP8Pem.replace("\n", "\\n")
            val client = AppleOAuthClient(props(), ObservationRegistry.NOOP)
            val key = client.parseEcPrivateKey(flattened)
            assertEquals("EC", key.algorithm)
        }

        @Test
        fun `손상된 base64 는 예외를 던진다`() {
            val client = AppleOAuthClient(props(), ObservationRegistry.NOOP)
            assertFailsWith<IllegalArgumentException> {
                client.parseEcPrivateKey("-----BEGIN PRIVATE KEY-----\n!!!notbase64!!!\n-----END PRIVATE KEY-----")
            }
        }
    }

    @Nested
    inner class BuildClientSecret {
        @Test
        fun `client_secret 헤더와 클레임이 Apple 명세를 따른다`() {
            val client = AppleOAuthClient(props(), ObservationRegistry.NOOP)
            val clientSecret = client.buildClientSecret()

            // public key 로 검증 (Apple 이 client_secret 받아 동일하게 검증함)
            val parsed =
                Jwts
                    .parser()
                    .verifyWith(testKeyPair.public)
                    .build()
                    .parseSignedClaims(clientSecret)

            assertEquals(testKid, parsed.header["kid"])
            assertEquals("ES256", parsed.header.algorithm)
            assertEquals("TEST_TEAM_ID", parsed.payload.issuer)
            assertEquals("com.test.service", parsed.payload.subject)
            assertTrue(
                parsed.payload.audience.contains("https://appleid.apple.com"),
                "aud 에 https://appleid.apple.com 이 포함돼야 한다: ${parsed.payload.audience}",
            )

            val iat = parsed.payload.issuedAt
            val exp = parsed.payload.expiration
            assertNotNull(iat)
            assertNotNull(exp)
            val ttlMs = exp.time - iat.time
            assertTrue(ttlMs > 0, "exp 는 iat 보다 미래여야 한다")
            // Apple 정책: client_secret 유효기간 최대 6개월
            val sixMonthsMs = 180L * 24 * 60 * 60 * 1000
            assertTrue(ttlMs <= sixMonthsMs, "exp - iat 는 6개월 이하여야 한다 (실제 ${ttlMs}ms)")
        }
    }

    @Nested
    inner class VerifyIdToken {
        @Test
        fun `v1 - aud 가 clientId 와 일치하면 OAuthUserInfo 를 반환한다`() {
            val client = AppleOAuthClient(props(), ObservationRegistry.NOOP)
            val sub = "001234.abcdef.5678"
            val idToken = signAppleIdToken(audience = "com.test.service", subject = sub)

            val result = verifyIntegrated(client, idToken, expectedAud = "com.test.service", jwksJson = testJwksJson)

            assertEquals(OAuthProvider.APPLE, result.provider)
            assertEquals(sub, result.socialId)
            assertEquals(null, result.profileImage)
        }

        @Test
        fun `v2 - aud 가 bundleId 와 일치하면 OAuthUserInfo 를 반환한다`() {
            val client = AppleOAuthClient(props(), ObservationRegistry.NOOP)
            val sub = "002345.bcdefg.6789"
            val idToken = signAppleIdToken(audience = "com.test.app", subject = sub)

            val result = verifyIntegrated(client, idToken, expectedAud = "com.test.app", jwksJson = testJwksJson)

            assertEquals(sub, result.socialId)
        }

        @Test
        fun `email 클레임이 있으면 OAuthUserInfo,email 로 추출한다 (최초 동의)`() {
            val client = AppleOAuthClient(props(), ObservationRegistry.NOOP)
            val idToken =
                signAppleIdToken(
                    audience = "com.test.service",
                    subject = "001234.abcdef.5678",
                    email = "user@privaterelay.appleid.com",
                )

            val result = verifyIntegrated(client, idToken, expectedAud = "com.test.service", jwksJson = testJwksJson)

            assertEquals("user@privaterelay.appleid.com", result.email)
        }

        @Test
        fun `email 클레임이 없으면 email 은 null 이다 (2회차·미동의)`() {
            val client = AppleOAuthClient(props(), ObservationRegistry.NOOP)
            val idToken = signAppleIdToken(audience = "com.test.service", subject = "001234.abcdef.5678")

            val result = verifyIntegrated(client, idToken, expectedAud = "com.test.service", jwksJson = testJwksJson)

            assertEquals(null, result.email)
        }

        @Test
        fun `email 클레임이 빈 문자열이면 null 로 정규화된다`() {
            val client = AppleOAuthClient(props(), ObservationRegistry.NOOP)
            val idToken = signAppleIdToken(audience = "com.test.service", subject = "001234.abcdef.5678", email = "")

            val result = verifyIntegrated(client, idToken, expectedAud = "com.test.service", jwksJson = testJwksJson)

            assertEquals(null, result.email)
        }

        @Test
        fun `sub 누락 - OAuthException providerError 를 던진다 (500 누출 방지)`() {
            val client = AppleOAuthClient(props(), ObservationRegistry.NOOP)
            val now = Date()
            val idTokenWithoutSub =
                Jwts
                    .builder()
                    .header()
                    .add("kid", testKid)
                    .and()
                    .issuer("https://appleid.apple.com")
                    // subject 생략 — Apple 비정상 응답·공격성 JWT 시뮬레이션
                    .audience()
                    .add("com.test.service")
                    .and()
                    .issuedAt(now)
                    .expiration(Date(now.time + 60_000))
                    .signWith(testKeyPair.private)
                    .compact()

            val ex =
                assertFailsWith<OAuthException> {
                    verifyIntegrated(client, idTokenWithoutSub, expectedAud = "com.test.service", jwksJson = testJwksJson)
                }
            assertEquals(502, ex.httpStatus.value())
        }

        @Test
        fun `aud 불일치 - OAuthException providerError 를 던진다`() {
            val client = AppleOAuthClient(props(), ObservationRegistry.NOOP)
            val idToken = signAppleIdToken(audience = "com.other.service", subject = "001234.xx")

            val ex =
                assertFailsWith<OAuthException> {
                    verifyIntegrated(client, idToken, expectedAud = "com.test.service", jwksJson = testJwksJson)
                }
            assertEquals(502, ex.httpStatus.value())
        }

        @Test
        fun `issuer 가 Apple 이 아니면 OAuthException providerError 를 던진다`() {
            val client = AppleOAuthClient(props(), ObservationRegistry.NOOP)
            val idToken =
                signAppleIdToken(
                    audience = "com.test.service",
                    subject = "001234.xx",
                    issuer = "https://attacker.example.com",
                )

            assertFailsWith<OAuthException> {
                verifyIntegrated(client, idToken, expectedAud = "com.test.service", jwksJson = testJwksJson)
            }
        }

        @Test
        fun `서명 키가 JWKS 에 없는 kid 면 OAuthException providerError 를 던진다`() {
            val client = AppleOAuthClient(props(), ObservationRegistry.NOOP)
            // JWKS 에 없는 kid 로 서명된 토큰
            val foreignKeyPair = generateEcKeyPair()
            val now = Date()
            val idToken =
                Jwts
                    .builder()
                    .header()
                    .add("kid", "UNKNOWN_KID")
                    .and()
                    .issuer("https://appleid.apple.com")
                    .subject("001234.xx")
                    .audience()
                    .add("com.test.service")
                    .and()
                    .issuedAt(now)
                    .expiration(Date(now.time + 60_000))
                    .signWith(foreignKeyPair.private)
                    .compact()

            assertFailsWith<OAuthException> {
                verifyIntegrated(client, idToken, expectedAud = "com.test.service", jwksJson = testJwksJson)
            }
        }

        @Test
        fun `서명이 위조되면 OAuthException providerError 를 던진다`() {
            val client = AppleOAuthClient(props(), ObservationRegistry.NOOP)
            // 같은 kid 로 다른 키쌍으로 서명 → JWKS 의 public key 로 verify 실패
            val foreignKeyPair = generateEcKeyPair()
            val now = Date()
            val idToken =
                Jwts
                    .builder()
                    .header()
                    .add("kid", testKid)
                    .and()
                    .issuer("https://appleid.apple.com")
                    .subject("001234.xx")
                    .audience()
                    .add("com.test.service")
                    .and()
                    .issuedAt(now)
                    .expiration(Date(now.time + 60_000))
                    .signWith(foreignKeyPair.private)
                    .compact()

            assertFailsWith<OAuthException> {
                verifyIntegrated(client, idToken, expectedAud = "com.test.service", jwksJson = testJwksJson)
            }
        }
    }

    @Nested
    inner class KidRotationRetryTrigger {
        // JWKS refresh+재시도는 "kid 가 현재 JWKS 에 없음"(=Apple 키 회전) 일 때만 일어난다.
        // isKidRotation 이 그 판별이라, 키 회전과 그 외 실패(서명 위조 등)를 구분하는지 검증한다.

        @Test
        fun `JWKS 에 없는 kid 는 키 회전으로 보고 재시도 트리거다`() {
            val client = AppleOAuthClient(props(), ObservationRegistry.NOOP)
            val foreign = generateEcKeyPair()
            val unknownKidToken =
                signAppleIdToken(
                    audience = "com.test.service",
                    subject = "001234.xx",
                    kid = "UNKNOWN_KID",
                    privateKey = foreign.private,
                )

            val ex = assertFailsWith<Exception> { client.parseIdToken(unknownKidToken, testJwksJson) }
            assertTrue(client.isKidRotation(ex), "kid 불일치는 JWKS 재조회 재시도 트리거여야 한다: $ex")
        }

        @Test
        fun `kid 는 맞지만 서명이 위조된 경우는 재시도 트리거가 아니다`() {
            val client = AppleOAuthClient(props(), ObservationRegistry.NOOP)
            val foreign = generateEcKeyPair()
            // kid 는 JWKS 에 있는 testKid 지만 다른 키로 서명 → 서명 검증 실패(키 회전 아님).
            // JWKS 를 다시 받아도 복구되지 않으므로 재시도하면 안 된다.
            val forgedToken =
                signAppleIdToken(
                    audience = "com.test.service",
                    subject = "001234.xx",
                    kid = testKid,
                    privateKey = foreign.private,
                )

            val ex = assertFailsWith<Exception> { client.parseIdToken(forgedToken, testJwksJson) }
            assertFalse(client.isKidRotation(ex), "서명 위조는 JWKS refresh 로 복구 안 되므로 재시도 트리거가 아니어야 한다: $ex")
        }
    }

    @Nested
    inner class VerifyNotification {
        // 서버-서버 알림 검증의 순수 부분(aud 검증 + events 파싱)을 외부 호출(JWKS) 없이 망라한다.
        // 서명·issuer·kid 검증은 id_token 과 같은 parseIdToken 경로라 위 VerifyIdToken·KidRotation 테스트가 커버한다.

        private fun eventClaims(
            audience: String,
            eventsJson: String?,
        ): io.jsonwebtoken.Claims {
            val client = AppleOAuthClient(props(), ObservationRegistry.NOOP)
            val now = Date()
            val builder =
                Jwts
                    .builder()
                    .header()
                    .add("kid", testKid)
                    .and()
                    .issuer("https://appleid.apple.com")
                    .audience()
                    .add(audience)
                    .and()
                    .issuedAt(now)
                    .expiration(Date(now.time + 60_000))
            eventsJson?.let { builder.claim("events", it) }
            val token = builder.signWith(testKeyPair.private).compact()
            return client.parseIdToken(token, testJwksJson)
        }

        @Test
        fun `aud 가 clientId 와 일치하면 events 를 파싱해 반환한다`() {
            val client = AppleOAuthClient(props(), ObservationRegistry.NOOP)
            val claims =
                eventClaims(
                    audience = "com.test.service",
                    eventsJson = """{"type":"account-delete","sub":"001234.abc","event_time":1700000000000}""",
                )

            val event = client.toNotificationEvent(claims)

            assertEquals(AppleNotificationEventType.ACCOUNT_DELETE, event.type)
            assertEquals("001234.abc", event.sub)
        }

        @Test
        fun `aud 가 bundleId 와 일치해도 통과한다`() {
            val client = AppleOAuthClient(props(), ObservationRegistry.NOOP)
            val claims =
                eventClaims(
                    audience = "com.test.app",
                    eventsJson = """{"type":"consent-revoked","sub":"002.def"}""",
                )

            val event = client.toNotificationEvent(claims)

            assertEquals(AppleNotificationEventType.CONSENT_REVOKED, event.type)
            assertEquals("002.def", event.sub)
        }

        @Test
        fun `aud 가 우리 client_id 가 아니면 401 로 거부한다 (위조 방어)`() {
            val client = AppleOAuthClient(props(), ObservationRegistry.NOOP)
            val claims =
                eventClaims(
                    audience = "com.attacker.service",
                    eventsJson = """{"type":"account-delete","sub":"001"}""",
                )

            val ex = assertFailsWith<AppleNotificationException> { client.toNotificationEvent(claims) }
            assertEquals(401, ex.httpStatus.value())
        }

        @Test
        fun `events 클레임이 없으면 401 로 거부한다`() {
            val client = AppleOAuthClient(props(), ObservationRegistry.NOOP)
            val claims = eventClaims(audience = "com.test.service", eventsJson = null)

            val ex = assertFailsWith<AppleNotificationException> { client.toNotificationEvent(claims) }
            assertEquals(401, ex.httpStatus.value())
        }

        @Test
        fun `events JSON 이 손상되면 401 로 거부한다`() {
            val client = AppleOAuthClient(props(), ObservationRegistry.NOOP)
            val claims = eventClaims(audience = "com.test.service", eventsJson = "{not-json")

            val ex = assertFailsWith<AppleNotificationException> { client.toNotificationEvent(claims) }
            assertEquals(401, ex.httpStatus.value())
        }
    }

    // ---- helpers ----

    private fun signAppleIdToken(
        audience: String,
        subject: String,
        issuer: String = "https://appleid.apple.com",
        kid: String = testKid,
        privateKey: java.security.PrivateKey = testKeyPair.private,
        email: String? = null,
    ): String {
        val now = Date()
        val builder =
            Jwts
                .builder()
                .header()
                .add("kid", kid)
                .and()
                .issuer(issuer)
                .subject(subject)
                .audience()
                .add(audience)
                .and()
                .issuedAt(now)
                .expiration(Date(now.time + 60_000))
        // email 클레임은 최초 동의 시에만 들어온다. null 이면 클레임 자체를 넣지 않아 2회차·미동의를 흉내낸다.
        email?.let { builder.claim("email", it) }
        return builder.signWith(privateKey).compact()
    }

    private fun generateEcKeyPair(): KeyPair {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec("secp256r1"))
        return gen.generateKeyPair()
    }

    private fun buildJwksJson(publicKey: ECPublicKey, kid: String): String {
        // 단위 테스트가 흉내내는 Apple JWKS — public key 만 노출하는 EC JWK 한 장.
        val jwk = Jwks.builder().key(publicKey).id(kid).build()
        // Apple JWKS 형식: { "keys": [ {<jwk>}, ... ] }
        val singleJwkJson = Jwks.json(jwk)
        return """{"keys":[$singleJwkJson]}"""
    }

    private fun encodeP8Pem(privateKey: ECPrivateKey): String {
        val der = privateKey.encoded
        val b64 = Base64.getEncoder().encodeToString(der)
        // 64자 단위 줄바꿈은 PEM 관례지만, parseEcPrivateKey 가 모든 개행을 strip 하므로 굳이 안 해도 됨.
        return "-----BEGIN PRIVATE KEY-----\n$b64\n-----END PRIVATE KEY-----"
    }

    // production 의 verifyIdTokenAndExtract 와 동일한 wrap 흐름을 흉내내는 테스트 통합 호출.
    // 외부 호출(getJwks) 우회를 위해 jwksJson 을 직접 받는다. parse 실패는 OAuthException 으로 wrap 되고,
    // claim 검증 실패(aud/sub) 는 verifyClaimsAndExtract 가 직접 OAuthException 을 던진다.
    private fun verifyIntegrated(
        client: AppleOAuthClient,
        idToken: String,
        expectedAud: String,
        jwksJson: String,
    ): OAuthUserInfo {
        val claims =
            try {
                client.parseIdToken(idToken, jwksJson)
            } catch (e: Exception) {
                throw OAuthException.providerError(e)
            }
        return client.verifyClaimsAndExtract(claims, expectedAud)
    }
}
