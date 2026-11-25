package com.depromeet.piki.explore.repository

import com.depromeet.piki.explore.domain.ExploreFeed
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration

@Component
class ExploreFeedStore(
    private val redisTemplate: StringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 전역 ObjectMapper 는 API 응답 규칙(시각을 KST 오프셋으로 변환)을 따른다. 저장 모양이 그 설정에 끌려가지 않게 따로 둔다
    private val objectMapper = jacksonObjectMapper()

    fun save(feed: ExploreFeed) {
        redisTemplate.opsForValue().set(RECENT_KEY, objectMapper.writeValueAsString(feed), TTL)
    }

    // 읽지 못하는 값은 없는 것으로 본다. 다음 주기의 재생성이 덮어쓴다
    fun find(): ExploreFeed? {
        val json = redisTemplate.opsForValue().get(RECENT_KEY) ?: return null
        return runCatching { objectMapper.readValue(json, ExploreFeed::class.java) }
            .onFailure { log.warn("탐색 피드를 읽지 못했다: {}", it.message) }
            .getOrNull()
    }

    companion object {
        const val RECENT_KEY = "explore:recent"

        // 갱신이 멈추면 낡은 피드가 계속 나가는 대신 키가 사라진다
        private val TTL = Duration.ofMinutes(10)
    }
}
