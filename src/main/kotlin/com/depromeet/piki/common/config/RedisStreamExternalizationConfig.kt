package com.depromeet.piki.common.config

import com.depromeet.piki.common.event.RedisStreamTransport
import org.springframework.beans.factory.BeanFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.modulith.events.EventExternalizationConfiguration
import org.springframework.modulith.events.support.EventExternalizerModuleListener
import tools.jackson.databind.ObjectMapper

@Configuration
class RedisStreamExternalizationConfig {
    @Bean
    fun redisStreamEventExternalizer(
        configuration: EventExternalizationConfiguration,
        redis: StringRedisTemplate,
        objectMapper: ObjectMapper,
        beanFactory: BeanFactory,
    ): EventExternalizerModuleListener = EventExternalizerModuleListener(configuration, RedisStreamTransport(redis, objectMapper), beanFactory)
}
