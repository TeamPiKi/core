package com.depromeet.piki.item.service

import io.lettuce.core.RedisBusyException
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
import java.util.UUID

@Configuration
class ParseResultStreamConfig {
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
            receive(
                Consumer.from(ParseResultConsumer.GROUP, "core-${UUID.randomUUID()}"),
                StreamOffset.create(ParseResultMessage.STREAM, ReadOffset.lastConsumed()),
                consumer,
            )
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
    }
}
