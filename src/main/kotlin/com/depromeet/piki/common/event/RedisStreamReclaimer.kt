package com.depromeet.piki.common.event

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
import java.util.concurrent.ConcurrentHashMap

@Component
class RedisStreamReclaimer(
    private val redis: StringRedisTemplate,
    private val subscriptions: List<RedisStreamSubscription>,
    @Qualifier(AsyncConfig.REDIS_STREAM_RECLAIM_EXECUTOR) private val executor: ThreadPoolTaskExecutor,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val reclaiming = ConcurrentHashMap.newKeySet<RedisStreamSubscription>()

    @Scheduled(fixedDelay = RECLAIM_INTERVAL_MS)
    fun requestReclaim() = subscriptions.forEach { subscription -> executor.execute { reclaimUnlessRunning(subscription) } }

    fun reclaim(
        subscription: RedisStreamSubscription,
        abandonedAfter: Duration,
    ) {
        val abandonedIds = findAbandonedIds(subscription, abandonedAfter)
        if (abandonedIds.isEmpty()) return
        takeOver(subscription, abandonedIds, abandonedAfter).forEach { process(subscription, it) }
    }

    // 한 스트림의 이어받기가 길어져도 다음 주기에 같은 스트림을 겹쳐 돌지 않고 다른 스트림의 스레드를 차지하지 않음
    private fun reclaimUnlessRunning(subscription: RedisStreamSubscription) {
        if (!reclaiming.add(subscription)) return
        try {
            reclaim(subscription, ABANDONED_AFTER)
        } catch (e: Exception) {
            log.warn("redis.stream.reclaim stream={} 이어받지 못함: {}", subscription.stream, e.message)
        } finally {
            reclaiming.remove(subscription)
        }
    }

    private fun findAbandonedIds(
        subscription: RedisStreamSubscription,
        abandonedAfter: Duration,
    ): List<RecordId> =
        redis
            .opsForStream<String, String>()
            .pending(subscription.stream, subscription.group, Range.unbounded<String>(), LIMIT, abandonedAfter)
            .map { it.id }
            .toList()

    private fun takeOver(
        subscription: RedisStreamSubscription,
        ids: List<RecordId>,
        abandonedAfter: Duration,
    ): List<MapRecord<String, String, String>> =
        redis
            .opsForStream<String, String>()
            .claim(subscription.stream, subscription.group, subscription.consumer, abandonedAfter, *ids.toTypedArray())

    private fun process(
        subscription: RedisStreamSubscription,
        message: MapRecord<String, String, String>,
    ) {
        try {
            subscription.listener.onMessage(message)
        } catch (e: Exception) {
            log.warn("redis.stream.reclaim stream={} message={} 이어받은 메시지를 처리하지 못함: {}", subscription.stream, message.id, e.message)
        }
    }

    companion object {
        private const val RECLAIM_INTERVAL_MS = 30_000L
        private const val LIMIT = 100L
        private val ABANDONED_AFTER = Duration.ofSeconds(30)
    }
}
