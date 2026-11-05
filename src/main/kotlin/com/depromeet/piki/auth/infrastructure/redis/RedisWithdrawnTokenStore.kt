package com.depromeet.piki.auth.infrastructure.redis

import com.depromeet.piki.auth.infrastructure.jwt.JwtProperties
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class RedisWithdrawnTokenStore(
    private val redisTemplate: StringRedisTemplate,
    private val jwtProperties: JwtProperties,
) : WithdrawnTokenStore {
    // TTL = access token 만료 시간. 이 시간이 지나면 탈퇴 회원이 들고 있던 access token 도 전부 만료되므로
    // 마커를 더 유지할 필요가 없다(자동 정리). 마커가 있는 동안엔 어떤 access token 이 와도 거부된다.
    override fun markWithdrawn(userId: UUID) {
        redisTemplate
            .opsForValue()
            .set(key(userId), MARKER, jwtProperties.accessTokenExpiry)
    }

    override fun isWithdrawn(userId: UUID): Boolean = redisTemplate.hasKey(key(userId)) ?: false

    private fun key(userId: UUID) = "$KEY_PREFIX$userId"

    companion object {
        private const val KEY_PREFIX = "withdrawn:"
        private const val MARKER = "1"
    }
}
