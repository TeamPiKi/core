package com.depromeet.piki.admin.access

import com.depromeet.piki.admin.config.AdminProperties
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration
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
}
