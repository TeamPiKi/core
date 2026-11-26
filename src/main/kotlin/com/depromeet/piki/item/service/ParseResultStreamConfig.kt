package com.depromeet.piki.item.service

import com.depromeet.piki.common.event.RedisStreamSubscription
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ParseResultStreamConfig {
    @Bean
    fun parseResultSubscription(consumer: ParseResultConsumer): RedisStreamSubscription =
        RedisStreamSubscription(
            stream = ParseResultMessage.STREAM,
            group = ParseResultMessage.GROUP,
            consumer = consumer.name,
            listener = consumer,
        )
}
