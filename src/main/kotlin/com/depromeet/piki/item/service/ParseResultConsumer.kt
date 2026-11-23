package com.depromeet.piki.item.service

import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.RedisStreamCommands.XDelOptions
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.stream.StreamListener
import org.springframework.stereotype.Component

@Component
class ParseResultConsumer(
    private val itemParsingService: ItemParsingService,
    private val itemParseResultRecorder: ItemParseResultRecorder,
    private val redis: StringRedisTemplate,
) : StreamListener<String, MapRecord<String, String, String>> {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun onMessage(message: MapRecord<String, String, String>) {
        ParseResultMessage.of(message.value)?.let(::apply) ?: log.error("item.parse.result.malformed message={} fields={}", message.id, message.value.keys)
        redis.opsForStream<String, String>().acknowledgeAndDelete(GROUP, message, XDelOptions.defaults())
    }

    private fun apply(result: ParseResultMessage) {
        val extracted = result.takeIf { it.isSuccess() }?.let { runCatchingException { it.toSnapshot() }.getOrNull() }
        extracted ?: return applyFailed(result)
        when (val settlement = UniqueConflictRetry.once("request=${result.requestId}") { itemParsingService.applyExtracted(result.requestId, extracted) }) {
            is ExtractionSettlement.Applied -> itemParseResultRecorder.recordSettled(result.requestId, settlement.status, "code=${result.code}")
            ExtractionSettlement.Superseded -> logSuperseded(result)
            ExtractionSettlement.Discarded -> Unit
        }
    }

    private fun applyFailed(result: ParseResultMessage) {
        if (!itemParsingService.applyFailed(result.requestId)) return logSuperseded(result)
        val reason = ItemParsingMetrics.failureReasonOf(result.toFailure())
        itemParseResultRecorder.recordFailed(result.requestId, reason, "code=${result.code}")
    }

    private fun logSuperseded(result: ParseResultMessage) {
        log.info("item.parse.result.superseded request={} 이미 닫힌 요청의 결과를 버림", result.requestId)
    }

    companion object {
        const val GROUP = "core"
    }
}
