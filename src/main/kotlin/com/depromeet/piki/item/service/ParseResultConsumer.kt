package com.depromeet.piki.item.service

import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.product.service.ProductSnapshot
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.RedisStreamCommands.XDelOptions
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.stream.StreamListener
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class ParseResultConsumer(
    private val itemParsingService: ItemParsingService,
    private val itemParseResultRecorder: ItemParseResultRecorder,
    private val redis: StringRedisTemplate,
) : StreamListener<String, MapRecord<String, String, String>> {
    private val log = LoggerFactory.getLogger(javaClass)

    val name: String = "core-${UUID.randomUUID()}"

    override fun onMessage(message: MapRecord<String, String, String>) {
        ParseResultMessage.of(message.value)?.let(::apply) ?: log.error("item.parse.result.malformed message={} fields={}", message.id, message.value.keys)
        redis.opsForStream<String, String>().acknowledgeAndDelete(ParseResultMessage.GROUP, message, XDelOptions.defaults())
    }

    private fun apply(result: ParseResultMessage) {
        val extracted = result.takeIf { it.isSuccess() }?.let { runCatchingException { it.toSnapshot() }.getOrNull() }
        extracted ?: return applyFailed(result)
        val settlement =
            runCatchingException { UniqueConflictRetry.once("request=${result.requestId}") { itemParsingService.applyExtracted(result.requestId, extracted) } }
                .getOrElse { e -> if (e.isInvariantViolation()) return rejectReady(result, e) else throw e }
        when (settlement) {
            is ExtractionSettlement.Applied -> itemParseResultRecorder.recordSettled(result.requestId, settlement.status, "code=${result.code}${missingOf(extracted, settlement.status)}")
            ExtractionSettlement.Superseded -> logSuperseded(result)
            ExtractionSettlement.Discarded -> Unit
        }
    }

    private fun missingOf(
        extracted: ProductSnapshot,
        status: ItemStatus,
    ): String = extracted.takeIf { status == ItemStatus.INCOMPLETE }?.let { " missing=${ItemParsingMetrics.missingFieldsOf(it)}" }.orEmpty()

    // 같은 값으로 다시 돌려도 또 막히므로 실패로 닫음
    private fun rejectReady(
        result: ParseResultMessage,
        cause: Throwable,
    ) {
        log.warn("item.parse.error outbox={} reason={} READY 전이 거부", result.requestId, ParseFailureReason.READY_REJECTED.metricLabel, cause)
        close(result, ParseFailureReason.READY_REJECTED)
    }

    private fun applyFailed(result: ParseResultMessage) = close(result, ItemParsingMetrics.failureReasonOf(result.toFailure()))

    private fun close(
        result: ParseResultMessage,
        reason: ParseFailureReason,
    ) {
        if (!itemParsingService.applyFailed(result.requestId)) return logSuperseded(result)
        itemParseResultRecorder.recordFailed(result.requestId, reason, "code=${result.code}")
    }

    // require·check·error 가 던지는 예외. 일시 오류와 달리 재실행해도 결과가 같음
    private fun Throwable.isInvariantViolation(): Boolean = this is IllegalArgumentException || this is IllegalStateException

    private fun logSuperseded(result: ParseResultMessage) {
        log.info("item.parse.result.superseded request={} 이미 닫힌 요청의 결과를 버림", result.requestId)
    }
}
