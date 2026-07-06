package com.depromeet.piki.admin.access

import com.depromeet.piki.admin.config.AdminProperties
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.util.encoders.Hex
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Discord 인터랙션 Ed25519 서명 검증(#654) — 공개 엔드포인트의 유일한 보호막이라 분기를 단위로 망라한다.
// 테스트가 Discord 와 동일하게 앱 개인키로 {timestamp}{body} 를 서명해 넣고, 공개키로 검증한다.
class DiscordInteractionVerifierTest {
    private val keyPair =
        Ed25519KeyPairGenerator().apply {
            init(Ed25519KeyGenerationParameters(SecureRandom()))
        }.generateKeyPair()
    private val privateKey = keyPair.private as Ed25519PrivateKeyParameters
    private val publicKeyHex = Hex.toHexString((keyPair.public as Ed25519PublicKeyParameters).encoded)

    private val verifier = DiscordInteractionVerifier(AdminProperties(discordPublicKey = publicKeyHex))

    // Discord 와 동일: {timestamp}{body} 를 개인키로 Ed25519 서명 → hex.
    private fun sign(
        ts: String,
        body: String,
        key: Ed25519PrivateKeyParameters = privateKey,
    ): String {
        val message = (ts + body).toByteArray(Charsets.UTF_8)
        val signer = Ed25519Signer().apply { init(true, key); update(message, 0, message.size) }
        return Hex.toHexString(signer.generateSignature())
    }

    private fun now() = Instant.now().epochSecond.toString()

    @Test
    fun `유효 서명과 최근 timestamp 면 통과한다`() {
        val ts = now()
        val body = """{"type":1}"""
        assertTrue(verifier.verify(sign(ts, body), ts, body))
    }

    @Test
    fun `본문이 변조되면 거부한다`() {
        val ts = now()
        val sig = sign(ts, """{"type":1}""")
        assertFalse(verifier.verify(sig, ts, """{"type":2}"""))
    }

    @Test
    fun `서명이 안 맞으면 거부한다`() {
        assertFalse(verifier.verify("deadbeef", now(), """{"type":1}"""))
    }

    @Test
    fun `다른 키로 만든 서명은 거부한다`() {
        val otherKey =
            Ed25519KeyPairGenerator().apply {
                init(Ed25519KeyGenerationParameters(SecureRandom()))
            }.generateKeyPair().private as Ed25519PrivateKeyParameters
        val ts = now()
        val body = "b"
        assertFalse(verifier.verify(sign(ts, body, otherKey), ts, body))
    }

    @Test
    fun `3분을 넘은 timestamp 는 거부한다 (replay 방지)`() {
        val ts = (Instant.now().epochSecond - 181).toString()
        assertFalse(verifier.verify(sign(ts, "b"), ts, "b"))
    }

    @Test
    fun `미래로 3분을 넘은 timestamp 도 거부한다`() {
        val ts = (Instant.now().epochSecond + 181).toString()
        assertFalse(verifier.verify(sign(ts, "b"), ts, "b"))
    }

    @Test
    fun `3분 윈도우 안이면 과거·미래 모두 허용한다`() {
        val past = (Instant.now().epochSecond - 179).toString()
        val future = (Instant.now().epochSecond + 179).toString()
        assertTrue(verifier.verify(sign(past, "b"), past, "b"))
        assertTrue(verifier.verify(sign(future, "b"), future, "b"))
    }

    @Test
    fun `timestamp 나 서명이 없으면 거부한다`() {
        assertFalse(verifier.verify(null, now(), "b"))
        assertFalse(verifier.verify(sign(now(), "b"), null, "b"))
    }

    @Test
    fun `숫자가 아닌 timestamp 는 거부한다`() {
        assertFalse(verifier.verify("00", "not-a-number", "b"))
    }

    @Test
    fun `비-hex 서명은 거부한다`() {
        assertFalse(verifier.verify("zzzz", now(), "b"))
    }

    @Test
    fun `공개키 미설정이면 항상 거부한다 (로컬·미설정 환경)`() {
        val noKey = DiscordInteractionVerifier(AdminProperties(discordPublicKey = ""))
        val ts = now()
        assertFalse(noKey.verify(sign(ts, "b"), ts, "b"))
    }
}
