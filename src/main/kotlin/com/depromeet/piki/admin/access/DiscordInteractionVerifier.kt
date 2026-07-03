package com.depromeet.piki.admin.access

import com.depromeet.piki.admin.config.AdminProperties
import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.util.encoders.Hex
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import kotlin.math.abs

// Discord 인터랙션(슬래시커맨드) 진위 검증 — 공개 엔드포인트(/admin-access/discord)의 유일한 보호막.
// Discord 는 `{timestamp}{rawBody}` 를 앱 개인키로 Ed25519 서명해 X-Signature-Ed25519(hex)·X-Signature-Timestamp 로 보낸다.
// 앱 공개키(discordPublicKey)로 검증한다. 공개키가 없으면(로컬) 항상 거부 — 로컬은 Discord 를 안 쓰고 localBypass 로 게이트를 건너뛴다.
//
// JDK EdEC 는 raw 32B 공개키를 PublicKey 로 만들려면 좌표 디코딩(바이트 반전·x-sign 비트)이 필요해 깨지기 쉽다.
// BouncyCastle 의 Ed25519PublicKeyParameters(raw, 0) + Ed25519Signer 는 raw 키를 그대로 받아 검증한다.
@Component
@ConditionalOnAdminEnabled
class DiscordInteractionVerifier(
    private val adminProperties: AdminProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun verify(
        signatureHex: String?,
        timestamp: String?,
        rawBody: String,
    ): Boolean {
        val publicKeyHex = adminProperties.discordPublicKey.ifBlank { return false }
        timestamp ?: return false
        signatureHex ?: return false

        // replay 방지 — 서명 대상에 든 timestamp(epoch sec)가 현재로부터 3분 이내여야 한다. 서명이 timestamp 를 덮어
        // 위조는 불가하나, 캡처한 유효 요청의 재전송(응답 본문으로 grant 링크가 유출)을 이 윈도우가 막는다.
        val ts = timestamp.toLongOrNull() ?: return false
        if (abs(Instant.now().epochSecond - ts) > REPLAY_WINDOW_SECONDS) return false

        // 공개키·서명 hex 파싱과 검증을 통째로 감싼다 — 잘못된 형식(공개키 오설정·변조 서명)은 예외 대신 false 로 흘려
        // 공개 엔드포인트가 스택트레이스로 죽지 않게 한다. Hex.decode 는 비-hex 에 예외를 던진다.
        return runCatching {
            val publicKey = Ed25519PublicKeyParameters(Hex.decode(publicKeyHex), 0)
            val signature = Hex.decode(signatureHex)
            // Discord 명세: 서명 대상 메시지 = timestamp 문자열 + raw 요청 본문.
            val message = (timestamp + rawBody).toByteArray(Charsets.UTF_8)
            Ed25519Signer().apply {
                init(false, publicKey)
                update(message, 0, message.size)
            }.verifySignature(signature)
        }.getOrElse { e ->
            // 공개키 형식 오류는 운영 설정 실수라 남긴다(빈도 낮음). 변조 서명은 흔한 공격 노이즈라 로깅하지 않는다.
            log.debug("Discord 인터랙션 서명 검증 실패", e)
            false
        }
    }

    companion object {
        private const val REPLAY_WINDOW_SECONDS = 180L
    }
}
