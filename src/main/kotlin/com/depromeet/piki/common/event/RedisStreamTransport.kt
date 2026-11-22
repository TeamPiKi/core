package com.depromeet.piki.common.event

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.modulith.events.RoutingTarget
import org.springframework.modulith.events.support.EventExternalizationTransport
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.CompletableFuture

class RedisStreamTransport(
    private val redis: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) : EventExternalizationTransport {
    override fun externalize(
        payload: Any,
        target: RoutingTarget,
    ): CompletableFuture<*> {
        val recordId = redis.opsForStream<String, String>().add(target.target, fieldsOf(payload))
        return CompletableFuture.completedFuture(recordId)
    }

    private fun fieldsOf(payload: Any): Map<String, String> =
        objectMapper
            .convertValue(payload, FIELDS)
            .mapValues { (_, value) -> value.toString() }

    companion object {
        private val FIELDS = object : TypeReference<Map<String, Any>>() {}
    }
}
