package com.depromeet.piki.item.service

import org.slf4j.LoggerFactory
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

    override fun onMessage(record: MapRecord<String, String, String>) {
        ParseResultMessage.of(record.value)?.let(::apply) ?: log.error("item.parse.result.malformed record={} fields={}", record.id, record.value.keys)
        redis.opsForStream<String, String>().acknowledge(ParseResultMessage.STREAM, GROUP, record.id)
    }

    private fun apply(message: ParseResultMessage) {
        val extracted = message.takeIf { it.isSuccess() }?.let { runCatchingException { it.toSnapshot() }.getOrNull() }
        extracted ?: return applyFailed(message)
        when (val settlement = UniqueConflictRetry.once("request=${message.requestId}") { itemParsingService.applyExtracted(message.requestId, extracted) }) {
            is ExtractionSettlement.Applied -> itemParseResultRecorder.recordSettled(message.requestId, settlement.status, "code=${message.code}")
            ExtractionSettlement.Superseded -> logSuperseded(message)
            ExtractionSettlement.Discarded -> Unit
        }
    }

    private fun applyFailed(message: ParseResultMessage) {
        if (!itemParsingService.applyFailed(message.requestId)) return logSuperseded(message)
        val reason = ItemParsingMetrics.failureReasonOf(message.toFailure())
        itemParseResultRecorder.recordFailed(message.requestId, reason, "code=${message.code}")
    }

    private fun logSuperseded(message: ParseResultMessage) {
        log.info("item.parse.result.superseded request={} 이미 닫힌 요청의 결과를 버림", message.requestId)
    }

    companion object {
        const val GROUP = "core"
    }
}
