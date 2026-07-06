package com.depromeet.piki.admin.access

import com.depromeet.piki.admin.config.AdminProperties
import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// grant 토큰을 HMAC 로 서명·검증한다. 인터랙션 엔드포인트(예: prod)가 발급하고, 링크 클릭 시 선택한 대상 env 가 검증한다 —
// 두 env 가 달라도 공유키(grantSigningKey)로 검증되므로, 환경별 Redis 공유 없이 cross-env 원타임 링크가 가능하다.
// 토큰 = base64url(payload JSON) + "." + HMAC-SHA256(key, base64url) hex. env 매칭·one-time 소비는 AdminAllowlistService 가 한다.
@Component
@ConditionalOnAdminEnabled
class GrantTokenCodec(
    private val adminProperties: AdminProperties,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun issue(
        userId: String,
        name: String,
        env: String,
    ): String {
        val exp = Instant.now().epochSecond + adminProperties.grantTokenTtl.seconds
        val nonce = UUID.randomUUID().toString().replace("-", "")
        // exp 는 문자열로 담아 파싱 API 불확실성을 피한다(asString 만 사용).
        val payload =
            objectMapper.writeValueAsString(
                mapOf("u" to userId, "n" to name, "e" to env, "x" to exp.toString(), "id" to nonce),
            )
        val payloadB64 = B64.encodeToString(payload.toByteArray(Charsets.UTF_8))
        return "$payloadB64.${hmacHex(payloadB64)}"
    }

    // HMAC·만료까지 검증한 claims 반환. 서명 불일치·만료·형식 오류·키 미설정이면 null. env 매칭·one-time 은 호출부(소비 env)가 한다.
    fun verify(token: String): GrantClaims? {
        adminProperties.grantSigningKey.ifBlank { return null }
        return runCatching {
            val dot = token.indexOf('.')
            if (dot <= 0 || dot == token.length - 1) return null
            val payloadB64 = token.substring(0, dot)
            val sig = token.substring(dot + 1)
            // 상수시간 비교로 타이밍 공격 방지.
            if (!MessageDigest.isEqual(hmacHex(payloadB64).toByteArray(), sig.toByteArray())) return null

            val json = objectMapper.readTree(String(B64D.decode(payloadB64), Charsets.UTF_8))
            val exp = json.path("x").takeIf { it.isString }?.asString()?.toLongOrNull() ?: return null
            if (Instant.now().epochSecond > exp) return null
            GrantClaims(
                userId = json.path("u").takeIf { it.isString }?.asString() ?: return null,
                name = json.path("n").takeIf { it.isString }?.asString() ?: return null,
                env = json.path("e").takeIf { it.isString }?.asString() ?: return null,
                nonce = json.path("id").takeIf { it.isString }?.asString() ?: return null,
            )
        }.getOrElse { e ->
            log.debug("grant 토큰 검증 실패", e)
            null
        }
    }

    private fun hmacHex(data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(adminProperties.grantSigningKey.toByteArray(), "HmacSHA256"))
        return mac.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val B64 = Base64.getUrlEncoder().withoutPadding()
        private val B64D = Base64.getUrlDecoder()
    }
}

data class GrantClaims(
    val userId: String,
    val name: String,
    val env: String,
    val nonce: String,
)
