package com.depromeet.piki.item.service

import com.depromeet.piki.common.config.AsyncConfig
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.domain.Range
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class ParseResultReclaimer(
    private val redis: StringRedisTemplate,
    private val consumer: ParseResultConsumer,
    @Qualifier(AsyncConfig.PARSE_RESULT_RECLAIM_EXECUTOR) private val executor: ThreadPoolTaskExecutor,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = RECLAIM_INTERVAL_MS)
    fun requestReclaim() = executor.execute { reclaim(ABANDONED_AFTER) }

    fun reclaim(abandonedAfter: Duration) {
        val abandonedIds = findAbandonedIds(abandonedAfter)
        if (abandonedIds.isEmpty()) return
        takeOver(abandonedIds, abandonedAfter).forEach(::process)
    }

    private fun findAbandonedIds(abandonedAfter: Duration): List<RecordId> =
        redis
            .opsForStream<String, String>()
            .pending(ParseResultMessage.STREAM, ParseResultConsumer.GROUP, Range.unbounded<String>(), LIMIT, abandonedAfter)
            .map { it.id }
            .toList()

    private fun takeOver(
        ids: List<RecordId>,
        abandonedAfter: Duration,
    ): List<MapRecord<String, String, String>> =
        redis
            .opsForStream<String, String>()
            .claim(ParseResultMessage.STREAM, ParseResultConsumer.GROUP, consumer.name, abandonedAfter, *ids.toTypedArray())

    private fun process(message: MapRecord<String, String, String>) {
        runCatchingException { consumer.onMessage(message) }
            .onFailure { e -> log.warn("item.parse.result.reclaim message={} 이어받은 결과를 반영하지 못함: {}", message.id, e.message) }
    }

    companion object {
        private const val RECLAIM_INTERVAL_MS = 30_000L
        private const val LIMIT = 100L
        private val ABANDONED_AFTER = Duration.ofSeconds(30)
    }
}
