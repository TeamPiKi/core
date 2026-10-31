package com.depromeet.piki.item.service

import com.depromeet.piki.common.config.AsyncConfig
import com.depromeet.piki.image.domain.ProductImageKey
import com.depromeet.piki.image.service.ImageSnapshotExtractor
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.domain.ParseAttempt
import com.depromeet.piki.item.domain.ParseInput
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.LinkSnapshotExtractor
import com.depromeet.piki.product.service.ProductSnapshot
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
    private val itemParseResultRecorder: ItemParseResultRecorder,
    private val observationRegistry: ObservationRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async(AsyncConfig.ITEM_PARSING_EXECUTOR)
    fun parse(attempt: ParseAttempt) {
        val input = runCatchingException { itemParsingService.inputOf(attempt.itemParseOutboxId) }.getOrElse { return failWithoutSource(attempt, it) }
        when (input) {
            is ParseInput.Link -> parseLink(attempt, input.link)
            is ParseInput.Image -> parseImage(attempt, input.key)
        }
    }

    private fun failWithoutSource(
        attempt: ParseAttempt,
        cause: Throwable,
    ) {
        log.error("item parse outbox {} 의 입력을 읽지 못해 실패로 종결", attempt.itemParseOutboxId, cause)
        if (markFailedQuietly(attempt)) itemParseResultRecorder.recordFailed(attempt.itemParseOutboxId, ParseFailureReason.INTERNAL_ERROR, "input=unreadable")
    }

    private fun parseLink(
        attempt: ParseAttempt,
        link: ProductLink,
    ) {
        extractAndSettle(attempt, "type=link url=\"$link\"") { linkSnapshotExtractor.extract(link) }
    }

    private fun parseImage(
        attempt: ParseAttempt,
        key: ProductImageKey,
    ) {
        extractAndSettle(attempt, "type=image key=$key") { imageSnapshotExtractor.extract(key) }
    }

    private fun extractAndSettle(
        attempt: ParseAttempt,
        logSubject: String,
        extract: () -> ProductSnapshot,
    ) {
        Observation.createNotStarted(PARSE_OBSERVATION, observationRegistry).observe(
            Runnable {
                val started = System.nanoTime()
                runCatchingException(extract).fold(
                    onSuccess = { settle(attempt, logSubject, it, started) },
                    onFailure = { failExtraction(attempt, logSubject, it, started) },
                )
            },
        )
    }

    private fun settle(
        attempt: ParseAttempt,
        logSubject: String,
        extracted: ProductSnapshot,
        started: Long,
    ) {
        runCatchingException { UniqueConflictRetry.once("outbox=${attempt.itemParseOutboxId}") { itemParsingService.markExtracted(attempt, extracted) } }.fold(
            onSuccess = { settlement ->
                when (settlement) {
                    is ExtractionSettlement.Applied -> recordSettled(attempt, logSubject, extracted, settlement.status, started)
                    ExtractionSettlement.Superseded -> logSuperseded(attempt)
                    ExtractionSettlement.Discarded -> Unit
                }
            },
            onFailure = { e ->
                markError(e)
                if (e.isInvariantViolation()) rejectReady(attempt, logSubject, e, started) else leaveForRecovery(attempt, e)
            },
        )
    }

    // 같은 값으로 다시 돌려도 또 막히므로 실패로 닫음
    private fun rejectReady(
        attempt: ParseAttempt,
        logSubject: String,
        cause: Throwable,
        started: Long,
    ) {
        log.warn("item.parse.error outbox={} reason={} READY 전이 거부", attempt.itemParseOutboxId, ParseFailureReason.READY_REJECTED.metricLabel, cause)
        fail(attempt, logSubject, ParseFailureReason.READY_REJECTED, started)
    }

    private fun leaveForRecovery(
        attempt: ParseAttempt,
        cause: Throwable,
    ) {
        log.error("item parse outbox {} 추출 결과 기록 실패, 회수 대기", attempt.itemParseOutboxId, cause)
    }

    // require·check·error 가 던지는 예외. 일시 오류와 달리 재실행해도 결과가 같음
    private fun Throwable.isInvariantViolation(): Boolean = this is IllegalArgumentException || this is IllegalStateException

    private fun recordSettled(
        attempt: ParseAttempt,
        logSubject: String,
        extracted: ProductSnapshot,
        status: ItemStatus,
        started: Long,
    ) {
        val missing = extracted.takeIf { status == ItemStatus.INCOMPLETE }?.let { " missing=${ItemParsingMetrics.missingFieldsOf(it)}" }.orEmpty()
        itemParseResultRecorder.recordSettled(attempt.itemParseOutboxId, status, "${latencyField(started)} $logSubject$missing")
    }

    private fun failExtraction(
        attempt: ParseAttempt,
        logSubject: String,
        cause: Throwable,
        started: Long,
    ) {
        markError(cause)
        val reason = ItemParsingMetrics.failureReasonOf(cause)
        if (fail(attempt, logSubject, reason, started)) log.info("item.parse.error outbox={} reason={} cause={}", attempt.itemParseOutboxId, reason.metricLabel, cause.message)
    }

    private fun markError(cause: Throwable) {
        observationRegistry.currentObservation?.error(cause)
    }

    private fun fail(
        attempt: ParseAttempt,
        logSubject: String,
        reason: ParseFailureReason,
        started: Long,
    ): Boolean {
        if (!markFailedQuietly(attempt)) return false
        itemParseResultRecorder.recordFailed(attempt.itemParseOutboxId, reason, "${latencyField(started)} $logSubject")
        return true
    }

    private fun markFailedQuietly(attempt: ParseAttempt): Boolean =
        runCatchingException { itemParsingService.markFailed(attempt) }
            .onSuccess { held -> if (!held) logSuperseded(attempt) }
            .onFailure { e -> log.error("item parse outbox {} FAILED 전이 실패, 회수 대기", attempt.itemParseOutboxId, e) }
            .getOrDefault(false)

    private fun logSuperseded(attempt: ParseAttempt) {
        log.warn("item.parse.superseded outbox={} recovery={} 행을 놓친 시도의 결과를 버림", attempt.itemParseOutboxId, attempt.recoveryCount)
    }

    private fun latencyField(started: Long): String = "latency=${(System.nanoTime() - started) / 1_000_000}ms"

    companion object {
        private const val PARSE_OBSERVATION = "item.parse"
    }
}
