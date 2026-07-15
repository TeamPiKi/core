package com.depromeet.piki.admin.access

import com.depromeet.piki.admin.config.AdminProperties
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertNull

// grant 토큰 HMAC 서명·검증(#654 cross-env). 발급 env 와 소비 env 가 달라도 공유키로 검증되는 게 핵심이라 분기를 단위로 망라한다.
class GrantTokenCodecTest {
    private val key = "0123456789abcdef0123456789abcdef"
    private val mapper = jacksonObjectMapper()

    private fun codec(
        signingKey: String = key,
        ttl: Duration = Duration.ofMinutes(3),
    ) = GrantTokenCodec(AdminProperties(grantSigningKey = signingKey, grantTokenTtl = ttl), mapper)

    @Test
    fun `발급한 토큰을 검증하면 claims 가 나온다`() {
        val c = codec()
        val claims = c.verify(c.issue("uid1", "홍길동", "prod"))!!
        assertEquals("uid1", claims.userId)
        assertEquals("홍길동", claims.name)
        assertEquals("prod", claims.env)
    }

    @Test
    fun `서명이 변조되면 거부한다`() {
        val token = codec().issue("uid1", "n", "dev")
        assertNull(codec().verify(token.dropLast(2) + "00"))
    }

    @Test
    fun `payload 가 변조되면 서명 불일치로 거부한다`() {
        val c = codec()
        val token = c.issue("uid1", "n", "dev")
        val otherPayload = c.issue("attacker", "n", "prod").substringBefore(".")
        val forged = otherPayload + token.substring(token.indexOf('.'))
        assertNull(c.verify(forged))
    }

    @Test
    fun `다른 키로 서명된 토큰은 거부한다`() {
        val issued = codec(signingKey = "ffffffffffffffffffffffffffffffff").issue("uid1", "n", "dev")
        assertNull(codec(signingKey = key).verify(issued))
    }

    @Test
    fun `만료된 토큰은 거부한다`() {
        val issued = codec(ttl = Duration.ofMinutes(-1)).issue("uid1", "n", "dev")
        assertNull(codec().verify(issued))
    }

    @Test
    fun `형식이 깨진 토큰은 거부한다`() {
        val c = codec()
        assertNull(c.verify("no-dot"))
        assertNull(c.verify(".onlysig"))
        assertNull(c.verify("onlypayload."))
        assertNull(c.verify("garbage.sig"))
    }

    @Test
    fun `서명키 미설정이면 항상 거부한다`() {
        val issued = codec(signingKey = key).issue("uid1", "n", "dev")
        assertNull(codec(signingKey = "").verify(issued))
    }

    @Test
    fun `issue-verify 라운드트립에서 목적지(dest)가 보존된다`() {
        // dest 에 따라 admin 세션 발급 여부가 갈리는 보안 로직이라 토큰에 위조 불가하게 실려야 한다(#733).
        val c = codec()
        GrantDest.entries.forEach { dest ->
            assertEquals(dest, c.verify(c.issue("uid1", "n", "dev", dest))?.dest, "dest=$dest 가 보존돼야 한다")
        }
    }

    @Test
    fun `d 클레임이 아예 없는 레거시 토큰은 ADMIN 으로 폴백한다 (하위호환)`() {
        // issue() 는 항상 d 를 넣으므로, d 없는 payload 를 같은 키로 직접 서명해 verify() 의 폴백을 실제로 검증한다.
        // (c.issue 로는 payload 에 이미 "d":"ADMIN" 이 있어 폴백 로직이 삭제돼도 통과해버린다.)
        assertEquals(GrantDest.ADMIN, codec().verify(signedToken(basePayload()))?.dest)
    }

    @Test
    fun `d 가 있으나 알 수 없는 값이면 토큰을 거부한다`() {
        // 미래에 새 목적지가 추가된 뒤 구버전 서버가 그 토큰을 ADMIN 세션으로 승격 처리하는 fail-open 을 막는다.
        assertNull(codec().verify(signedToken(basePayload().apply { put("d", "SUPERADMIN") })))
    }

    // d 를 넣지 않는 등 임의 payload 를 GrantTokenCodec 과 동일한 방식(base64url(json).HMAC-SHA256 hex)으로 서명한다.
    private fun basePayload(): MutableMap<String, String> =
        mutableMapOf(
            "u" to "uid1", "n" to "n", "e" to "dev",
            "x" to (Instant.now().epochSecond + 300).toString(), "id" to "nonce1",
        )

    private fun signedToken(payload: Map<String, String>): String {
        val b64 =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mapper.writeValueAsString(payload).toByteArray())
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.toByteArray(), "HmacSHA256")) }
        val sig = mac.doFinal(b64.toByteArray()).joinToString("") { "%02x".format(it) }
        return "$b64.$sig"
    }
}
