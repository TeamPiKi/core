package com.depromeet.piki.common.event

import io.lettuce.core.RedisBusyException
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.core.task.SimpleAsyncTaskExecutor
import org.springframework.data.redis.RedisSystemException
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.stream.StreamMessageListenerContainer
import org.springframework.stereotype.Component
import java.time.Duration

// 컨테이너의 stop 은 읽기를 그만두라는 표시만 하고 돌아옴. Redis 연결이 닫히기 전 단계에서 하던 한 건이 끝나기를 기다림
@Component
class RedisStreamListening(
    connectionFactory: RedisConnectionFactory,
    private val redis: StringRedisTemplate,
    subscriptions: List<RedisStreamSubscription>,
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)

    private val executor = SimpleAsyncTaskExecutor("redis-stream-consumer-").apply { setTaskTerminationTimeout(STOP_TIMEOUT.toMillis()) }

    private val container: StreamMessageListenerContainer<String, MapRecord<String, String, String>> =
        StreamMessageListenerContainer.create(
            connectionFactory,
            StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                .builder()
                .pollTimeout(POLL_TIMEOUT)
                .executor(executor)
                .build(),
        )

    init {
        subscriptions.forEach(::register)
    }

    override fun start() = container.start()

    override fun stop() {
        container.stop()
        executor.close()
    }

    override fun isRunning(): Boolean = container.isRunning

    private fun register(subscription: RedisStreamSubscription) {
        createGroupIfAbsent(subscription)
        container.register(
            StreamMessageListenerContainer.StreamReadRequest
                .builder(StreamOffset.create(subscription.stream, ReadOffset.lastConsumed()))
                .consumer(Consumer.from(subscription.group, subscription.consumer))
                .autoAcknowledge(false)
                // 기본값은 오류 한 번에 구독을 끊어 재기동 전까지 읽지 않음
                .cancelOnError { false }
                .errorHandler { e -> recover(subscription, e) }
                .build(),
            subscription.listener,
        )
    }

    // 읽기 루프에는 쉬는 구간이 없어 바로 실패하는 오류는 쉬지 않고 반복됨. 스트림이 사라진 경우는 그룹을 다시 만들어야 풀림
    private fun recover(
        subscription: RedisStreamSubscription,
        cause: Throwable,
    ) {
        log.error("redis.stream.consume stream={} 읽거나 처리하지 못함. 이어받기가 다시 처리", subscription.stream, cause)
        try {
            createGroupIfAbsent(subscription)
            Thread.sleep(RETRY_DELAY.toMillis())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            log.warn("redis.stream.consume stream={} 그룹을 다시 만들지 못함: {}", subscription.stream, e.message)
        }
    }

    private fun createGroupIfAbsent(subscription: RedisStreamSubscription) {
        try {
            redis.opsForStream<String, String>().createGroup(subscription.stream, ReadOffset.from("0"), subscription.group)
        } catch (e: RedisSystemException) {
            if (e.cause !is RedisBusyException) throw e
        }
    }

    companion object {
        // 연결의 명령 타임아웃(spring.data.redis.timeout)보다 짧아야 대기가 타임아웃으로 끊기지 않음
        private val POLL_TIMEOUT = Duration.ofSeconds(1)
        private val RETRY_DELAY = Duration.ofSeconds(1)
        private val STOP_TIMEOUT = Duration.ofSeconds(10)
    }
}
