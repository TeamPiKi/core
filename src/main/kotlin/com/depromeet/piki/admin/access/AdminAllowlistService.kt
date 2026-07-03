package com.depromeet.piki.admin.access

import com.depromeet.piki.admin.config.AdminProperties
import com.depromeet.piki.admin.config.ConditionalOnAdminEnabled
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.util.UUID

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

    // 현재 허용된 IP 목록 (/piki-admin list 용). 소수라 keys 스캔으로 충분.
    fun list(): List<AllowedIp> =
        redis.keys("$ALLOW_PREFIX*").map { key ->
            AllowedIp(ip = key.removePrefix(ALLOW_PREFIX), name = redis.opsForValue().get(key) ?: "?")
        }

    // 원타임 grant 토큰 발급 — Discord 사용자 신원을 짧게 보관한다. grant 링크 클릭(접속 IP 캡처) 때 소비한다.
    fun issueGrantToken(
        userId: String,
        name: String,
    ): String {
        val token = UUID.randomUUID().toString().replace("-", "")
        redis.opsForValue().set(grantKey(token), "$userId|$name", adminProperties.grantTokenTtl)
        return token
    }

    // 토큰 소비(1회) — 유효하면 신원 반환 후 삭제, 아니면 null.
    // getAndDelete(GETDEL) 로 조회·삭제를 원자화한다 — get 후 delete 분리 시 동시 요청이 같은 토큰을 두 번
    // 소비해(TOCTOU) 원타임 보장이 깨진다(같은 grant 링크를 두 곳에서 동시 열면 두 IP 모두 허용될 수 있음).
    fun consumeGrantToken(token: String): GrantIdentity? {
        val raw = redis.opsForValue().getAndDelete(grantKey(token)) ?: return null
        val parts = raw.split("|", limit = 2)
        return GrantIdentity(userId = parts[0], name = parts.getOrElse(1) { parts[0] })
    }

    private fun allowKey(ip: String) = "$ALLOW_PREFIX$ip"

    private fun grantKey(token: String) = "$GRANT_PREFIX$token"

    companion object {
        private const val ALLOW_PREFIX = "admin:allow:"
        private const val GRANT_PREFIX = "admin:grant:"
    }
}

data class AllowedIp(val ip: String, val name: String)

data class GrantIdentity(val userId: String, val name: String)
