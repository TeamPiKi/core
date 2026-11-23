package com.depromeet.piki.item.service

import io.lettuce.core.RedisBusyException
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.RedisSystemException
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.stream.StreamMessageListenerContainer
import java.time.Duration

@Configuration
class ParseResultStreamConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean(initMethod = "start", destroyMethod = "stop")
    fun parseResultListenerContainer(
        connectionFactory: RedisConnectionFactory,
        redis: StringRedisTemplate,
        consumer: ParseResultConsumer,
    ): StreamMessageListenerContainer<String, MapRecord<String, String, String>> {
        createGroupIfAbsent(redis)
        val options =
            StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                .builder()
                .pollTimeout(POLL_TIMEOUT)
                .build()
        return StreamMessageListenerContainer.create(connectionFactory, options).apply {
            register(
                StreamMessageListenerContainer.StreamReadRequest
                    .builder(StreamOffset.create(ParseResultMessage.STREAM, ReadOffset.lastConsumed()))
                    .consumer(Consumer.from(ParseResultConsumer.GROUP, consumer.name))
                    .autoAcknowledge(false)
                    // 기본값은 오류 한 번에 구독을 끊어 재기동 전까지 결과를 읽지 않음
                    .cancelOnError { false }
                    .errorHandler { e -> recover(redis, e) }
                    .build(),
                consumer,
            )
        }
    }

    // 읽기 루프에는 쉬는 구간이 없어 바로 실패하는 오류는 쉬지 않고 반복됨. 스트림이 사라진 경우는 그룹을 다시 만들어야 풀림
    private fun recover(
        redis: StringRedisTemplate,
        cause: Throwable,
    ) {
        log.error("item.parse.result.consume 결과를 읽거나 반영하지 못함. 이어받기가 다시 처리", cause)
        runCatchingException { createGroupIfAbsent(redis) }
        try {
            Thread.sleep(RETRY_DELAY.toMillis())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun createGroupIfAbsent(redis: StringRedisTemplate) {
        try {
            redis.opsForStream<String, String>().createGroup(ParseResultMessage.STREAM, ReadOffset.from("0"), ParseResultConsumer.GROUP)
        } catch (e: RedisSystemException) {
            if (e.cause !is RedisBusyException) throw e
        }
    }

    companion object {
        // 연결의 명령 타임아웃(spring.data.redis.timeout)보다 짧아야 대기가 타임아웃으로 끊기지 않음
        private val POLL_TIMEOUT = Duration.ofSeconds(1)
        private val RETRY_DELAY = Duration.ofSeconds(1)
    }
}
