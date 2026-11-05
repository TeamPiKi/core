package com.depromeet.piki.auth.infrastructure.redis

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class RedisOAuthStateStore(
    private val redisTemplate: StringRedisTemplate,
) : OAuthStateStore {
    override fun store(state: String) {
        redisTemplate.opsForValue().set(key(state), "1", STATE_TTL)
    }

    // DEL 은 키가 있으면 1, 없으면 0 을 반환한다. 1이면 유효한 state 가 원자적으로 소비된 것.
    // GET + DEL 분리 시 TOCTOU 가 생겨 두 요청이 동시에 통과할 수 있으므로 단일 DEL 로 원자화한다.
    override fun consumeIfValid(state: String): Boolean = redisTemplate.delete(key(state))

    private fun key(state: String) = "oauth:state:$state"

    companion object {
        private val STATE_TTL = Duration.ofMinutes(10)
    }
}
