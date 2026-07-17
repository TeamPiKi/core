package com.depromeet.piki.admin.access

import com.depromeet.piki.admin.config.AdminProperties
import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration

// 슬랙으로 등록한 "접근 허용 IP" 를 Redis 에 둔다. 이 한 allowlist 가 두 게이트를 다 받친다 —
// (1) prod /admin (AdminAccessFilter), (2) dev/staging 도메인 전체(EnvironmentAccessFilter).
//
// sliding TTL: /admin·도메인 접근 때마다 refresh 해 활동 중엔 안 끊기고(allowlistTtl, 기본 24h),
// 무활동이면 자동 만료(stale IP 자동 정리 — 모바일·집 IP 변동 흡수). 이름(슬랙 표시명)을 값으로 둬 목록·감사에 쓴다.
@Service
@ConditionalOnAdminEnabled
class AdminAllowlistService(
    private val redis: StringRedisTemplate,
    private val adminProperties: AdminProperties,
    private val grantTokenCodec: GrantTokenCodec,
) {
    // grant 가 IP 를 허용한다. name = 등록한 Discord 사용자 표시명.
    fun grant(
        ip: String,
        name: String,
    ) {
        redis.opsForValue().set(allowKey(ip), name, adminProperties.allowlistTtl)
    }

    fun isAllowed(ip: String): Boolean = redis.hasKey(allowKey(ip))

    // sliding — 접근 시 TTL 을 다시 채운다(활동 중엔 만료되지 않음).
    fun refresh(ip: String) {
        redis.expire(allowKey(ip), adminProperties.allowlistTtl)
    }

    fun revoke(ip: String) {
        redis.delete(allowKey(ip))
    }

    // 남은 세션 시간 — allowlist IP 의 Redis 잔여 TTL. 키가 없거나(만료·미등록) 만료 미설정이면 null.
    fun ttl(ip: String): Duration? = redis.getExpire(allowKey(ip)).takeIf { it > 0 }?.let { Duration.ofSeconds(it) }

    // 현재 허용된 IP 목록 (/piki-admin list 용). 소수라 keys 스캔으로 충분.
    fun list(): List<AllowedIp> =
        redis.keys("$ALLOW_PREFIX*").map { key ->
            AllowedIp(ip = key.removePrefix(ALLOW_PREFIX), name = redis.opsForValue().get(key) ?: "?")
        }

    // 원타임 grant 토큰 발급 — 신원+env 를 HMAC 서명한 stateless 토큰. 인터랙션 엔드포인트(예: prod)가 발급하고
    // 링크 클릭 시 선택한 env 가 소비한다. 환경별 Redis 공유 없이 cross-env 로 동작한다(서명·env·one-time 으로 안전).
    fun issueGrantToken(
        userId: String,
        name: String,
        env: String,
        dest: GrantDest = GrantDest.ADMIN,
    ): String = grantTokenCodec.issue(userId, name, env, dest)

    // 토큰 소비(1회) — 서명·만료 검증 후 (1) 토큰의 env 가 이 환경과 같고 (2) 처음 쓰는 토큰이면 신원 반환, 아니면 null.
    // one-time 은 nonce 를 SET NX(setIfAbsent)로 심어 보장한다 — 이미 있으면(재사용·동시 재클릭) false 라 거부(TOCTOU 차단).
    fun consumeGrantToken(
        token: String,
        currentEnv: String,
    ): GrantIdentity? {
        val claims = grantTokenCodec.verify(token) ?: return null
        if (claims.env != currentEnv) return null
        val fresh = redis.opsForValue().setIfAbsent(usedKey(claims.nonce), "1", adminProperties.grantTokenTtl) ?: false
        if (!fresh) return null
        return GrantIdentity(userId = claims.userId, name = claims.name, dest = claims.dest)
    }

    private fun allowKey(ip: String) = "$ALLOW_PREFIX$ip"

    private fun usedKey(nonce: String) = "$USED_PREFIX$nonce"

    companion object {
        private const val ALLOW_PREFIX = "admin:allow:"
        private const val USED_PREFIX = "admin:grant:used:"
    }
}

data class AllowedIp(val ip: String, val name: String)

data class GrantIdentity(val userId: String, val name: String, val dest: GrantDest)
