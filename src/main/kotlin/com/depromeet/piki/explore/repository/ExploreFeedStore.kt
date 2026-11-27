package com.depromeet.piki.explore.repository

import com.depromeet.piki.explore.domain.ExploreFeed
import com.depromeet.piki.explore.domain.ExplorePickFeed
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

    fun save(feed: ExploreFeed) = write(RECENT_KEY, feed)

    fun find(): ExploreFeed? = read(RECENT_KEY, ExploreFeed::class.java)

    fun savePicks(feed: ExplorePickFeed) = write(PICKS_KEY, feed)

    fun findPicks(): ExplorePickFeed? = read(PICKS_KEY, ExplorePickFeed::class.java)

    private fun write(
        key: String,
        value: Any,
    ) {
        redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), TTL)
    }

    // 읽지 못하는 값은 없는 것으로 본다. 다음 주기의 재생성이 덮어쓴다
    private fun <T> read(
        key: String,
        type: Class<T>,
    ): T? {
        val json = redisTemplate.opsForValue().get(key) ?: return null
        return runCatching { objectMapper.readValue(json, type) }
            .onFailure { log.warn("탐색 피드를 읽지 못했다: key={} {}", key, it.message) }
            .getOrNull()
    }

    companion object {
        const val RECENT_KEY = "explore:recent"
        const val PICKS_KEY = "explore:picks"

        // 갱신이 멈추면 낡은 피드가 계속 나가는 대신 키가 사라진다
        private val TTL = Duration.ofMinutes(10)
    }
}
