package com.depromeet.piki.item.service

import com.depromeet.piki.common.config.AsyncConfig
import com.depromeet.piki.image.domain.ImageKey
import com.depromeet.piki.image.service.ImageSnapshotExtractor
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.domain.ParseInput
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.LinkSnapshotExtractor
import com.depromeet.piki.product.service.ProductSnapshot
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

@Component
class ItemParser(
    private val linkSnapshotExtractor: LinkSnapshotExtractor,
    private val imageSnapshotExtractor: ImageSnapshotExtractor,
    private val itemParsingService: ItemParsingService,
    private val meterRegistry: MeterRegistry,
    private val observationRegistry: ObservationRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async(AsyncConfig.ITEM_PARSING_EXECUTOR)
    fun parse(itemParseOutboxId: Long) {
        val input = runCatchingException { itemParsingService.inputOf(itemParseOutboxId) }.getOrElse { return failWithoutSource(itemParseOutboxId, it) }
        when (input) {
            is ParseInput.Link -> parseLink(itemParseOutboxId, input.link)
            is ParseInput.Image -> parseImage(itemParseOutboxId, input.key)
        }
    }

    private fun failWithoutSource(
        itemParseOutboxId: Long,
        cause: Throwable,
    ) {
        log.error("item parse outbox {} 의 입력을 읽지 못해 실패로 종결", itemParseOutboxId, cause)
        if (markFailedQuietly(itemParseOutboxId)) {
            ItemParsingMetrics.record(meterRegistry, ItemParsingMetrics.RESULT_FAILED, ParseFailureReason.INTERNAL_ERROR.metricLabel)
        }
    }

    private fun parseLink(
        itemParseOutboxId: Long,
        link: ProductLink,
    ) {
        extractAndSettle(itemParseOutboxId, "type=link url=\"$link\"") { linkSnapshotExtractor.extract(link) }
    }

    private fun parseImage(
        itemParseOutboxId: Long,
        key: ImageKey,
    ) {
        extractAndSettle(itemParseOutboxId, "type=image key=$key") { imageSnapshotExtractor.extract(key) }
    }

    private fun extractAndSettle(
        itemParseOutboxId: Long,
        logSubject: String,
        extract: () -> ProductSnapshot,
    ) {
        Observation.createNotStarted(PARSE_OBSERVATION, observationRegistry).observe(
            Runnable {
                val started = System.nanoTime()
                runCatchingException(extract).fold(
                    onSuccess = { settle(itemParseOutboxId, logSubject, it, started) },
                    onFailure = { failExtraction(itemParseOutboxId, logSubject, it, started) },
                )
            },
        )
    }

    private fun settle(
        itemParseOutboxId: Long,
        logSubject: String,
        extracted: ProductSnapshot,
        started: Long,
    ) {
        runCatchingException { itemParsingService.markExtracted(itemParseOutboxId, extracted) }.fold(
            onSuccess = { settlement -> if (settlement is ExtractionSettlement.Applied) recordSettled(itemParseOutboxId, logSubject, extracted, settlement.status, started) },
            onFailure = { e ->
                markError(e)
                if (e.isInvariantViolation()) rejectReady(itemParseOutboxId, logSubject, e, started) else leaveForRecovery(itemParseOutboxId, e)
            },
        )
    }

    // 같은 값으로 다시 돌려도 또 막히므로 실패로 닫음
    private fun rejectReady(
        itemParseOutboxId: Long,
        logSubject: String,
        cause: Throwable,
        started: Long,
    ) {
        log.warn("item.parse.error outbox={} reason={} READY 전이 거부", itemParseOutboxId, ParseFailureReason.READY_REJECTED.metricLabel, cause)
        fail(itemParseOutboxId, logSubject, ParseFailureReason.READY_REJECTED, started)
    }

    private fun leaveForRecovery(
        itemParseOutboxId: Long,
        cause: Throwable,
    ) {
        log.error("item parse outbox {} 추출 결과 기록 실패, 회수 대기", itemParseOutboxId, cause)
    }

    // require·check·error 가 던지는 예외. 일시 오류와 달리 재실행해도 결과가 같음
    private fun Throwable.isInvariantViolation(): Boolean = this is IllegalArgumentException || this is IllegalStateException

    private fun recordSettled(
        itemParseOutboxId: Long,
        logSubject: String,
        extracted: ProductSnapshot,
        status: ItemStatus,
        started: Long,
    ) {
        val missing = extracted.takeIf { status == ItemStatus.INCOMPLETE }?.let { " missing=${ItemParsingMetrics.missingFieldsOf(it)}" }.orEmpty()
        recordResult(itemParseOutboxId, resultOf(status), reasonOf(status), started, "$logSubject$missing")
    }

    private fun failExtraction(
        itemParseOutboxId: Long,
        logSubject: String,
        cause: Throwable,
        started: Long,
    ) {
        markError(cause)
        val reason = ItemParsingMetrics.failureReasonOf(cause)
        if (fail(itemParseOutboxId, logSubject, reason, started)) log.info("item.parse.error outbox={} reason={} cause={}", itemParseOutboxId, reason.metricLabel, cause.message)
    }

    private fun markError(cause: Throwable) {
        observationRegistry.currentObservation?.error(cause)
    }

    private fun fail(
        itemParseOutboxId: Long,
        logSubject: String,
        reason: ParseFailureReason,
        started: Long,
    ): Boolean {
        if (!markFailedQuietly(itemParseOutboxId)) return false
        recordResult(itemParseOutboxId, ItemParsingMetrics.RESULT_FAILED, reason.metricLabel, started, logSubject)
        return true
    }

    private fun markFailedQuietly(itemParseOutboxId: Long): Boolean =
        runCatchingException { itemParsingService.markFailed(itemParseOutboxId) }
            .onFailure { e -> log.error("item parse outbox {} FAILED 전이 실패, 회수 대기", itemParseOutboxId, e) }
            .getOrDefault(false)

    private fun recordResult(
        itemParseOutboxId: Long,
        result: String,
        reason: String,
        started: Long,
        trailingFields: String,
    ) {
        val latencyMs = (System.nanoTime() - started) / 1_000_000
        log.info("item.parse.result outbox={} result={} reason={} latency={}ms {}", itemParseOutboxId, result, reason, latencyMs, trailingFields)
        ItemParsingMetrics.record(meterRegistry, result, reason)
    }

    private fun resultOf(status: ItemStatus): String =
        when (status) {
            ItemStatus.READY -> ItemParsingMetrics.RESULT_READY
            ItemStatus.INCOMPLETE -> ItemParsingMetrics.RESULT_INCOMPLETE
            ItemStatus.FAILED -> ItemParsingMetrics.RESULT_FAILED
            ItemStatus.PARSING -> error("추출 전이가 만들 수 없는 상태 $status")
        }

    private fun reasonOf(status: ItemStatus): String =
        if (status == ItemStatus.FAILED) ParseFailureReason.EXTRACT_QUALITY.metricLabel else ItemParsingMetrics.REASON_NONE

    companion object {
        private const val PARSE_OBSERVATION = "item.parse"
    }
}
