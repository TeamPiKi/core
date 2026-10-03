package com.depromeet.piki.item.service

import com.depromeet.piki.common.config.AsyncConfig
import com.depromeet.piki.common.storage.ImageStorage
import com.depromeet.piki.image.service.ImageSnapshotExtractor
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.LinkSnapshotExtractor
import com.depromeet.piki.product.service.ProductSnapshot
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.util.function.Supplier

@Component
class ItemParser(
    private val linkSnapshotExtractor: LinkSnapshotExtractor,
    private val imageSnapshotExtractor: ImageSnapshotExtractor,
    private val imageStorage: ImageStorage,
    private val itemIdentityRecorder: ItemIdentityRecorder,
    private val itemParsingService: ItemParsingService,
    private val meterRegistry: MeterRegistry,
    private val observationRegistry: ObservationRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async(AsyncConfig.ITEM_PARSING_EXECUTOR)
    fun parse(itemParseOutboxId: Long) {
        val item = itemParsingService.itemToParse(itemParseOutboxId) ?: return failWithoutSource(itemParseOutboxId)
        item.link?.let { parseLink(item.getId(), itemParseOutboxId, it) }
            ?: item.sourceImageKey?.let { parseImage(item.getId(), itemParseOutboxId, it) }
            ?: failWithoutSource(itemParseOutboxId)
    }

    private fun failWithoutSource(itemParseOutboxId: Long) {
        log.error("item parse outbox {} 에 파싱할 입력이 없어 실패로 종결", itemParseOutboxId)
        if (markFailedQuietly(itemParseOutboxId)) {
            ItemParsingMetrics.record(meterRegistry, ItemParsingMetrics.RESULT_FAILED, ParseFailureReason.INTERNAL_ERROR.metricLabel)
        }
    }

    private fun parseLink(
        itemId: Long,
        itemParseOutboxId: Long,
        link: ProductLink,
    ) {
        extractAndSettle(itemId, itemParseOutboxId, "type=link url=${link.safeLogString()}") { linkSnapshotExtractor.extract(link) }
            .extractedValue()
            ?.let { recordIdentity(itemId, it.finalUrl) }
    }

    private fun parseImage(
        itemId: Long,
        itemParseOutboxId: Long,
        imageKey: String,
    ) {
        val outcome = extractAndSettle(itemId, itemParseOutboxId, "type=image key=$imageKey") { imageSnapshotExtractor.extract(imageKey) }
        if (outcome.isApplied()) deleteRaw(imageKey)
    }

    private fun extractAndSettle(
        itemId: Long,
        itemParseOutboxId: Long,
        logSubject: String,
        extract: () -> ProductSnapshot,
    ): ParsingOutcome =
        // TODO: 관측 구간은 @Observed(AOP)로 뺄 수 있음
        Observation.createNotStarted(PARSE_OBSERVATION, observationRegistry).observe(
            Supplier {
                val started = System.nanoTime()
                runCatchingException(extract).fold(
                    onSuccess = { settle(itemId, itemParseOutboxId, logSubject, it, started) },
                    onFailure = { failExtraction(itemId, itemParseOutboxId, logSubject, it, started) },
                )
            },
        )

    private fun settle(
        itemId: Long,
        itemParseOutboxId: Long,
        logSubject: String,
        extracted: ProductSnapshot,
        started: Long,
    ): ParsingOutcome =
        runCatchingException { itemParsingService.markExtracted(itemParseOutboxId, extracted) }.fold(
            onSuccess = { status -> status?.let { recordSettled(itemId, logSubject, extracted, it, started) } ?: ParsingOutcome.NotApplied },
            onFailure = { e ->
                markError(e)
                if (e.isInvariantViolation()) rejectReady(itemId, itemParseOutboxId, logSubject, e, started) else leaveForRecovery(itemId, e)
            },
        )

    // 같은 값으로 다시 돌려도 또 막히므로 실패로 닫음
    private fun rejectReady(
        itemId: Long,
        itemParseOutboxId: Long,
        logSubject: String,
        cause: Throwable,
        started: Long,
    ): ParsingOutcome {
        log.warn("item.parse.error item={} reason={} READY 전이 거부", itemId, ParseFailureReason.READY_REJECTED.metricLabel, cause)
        return fail(itemId, itemParseOutboxId, logSubject, ParseFailureReason.READY_REJECTED, started)
    }

    private fun leaveForRecovery(
        itemId: Long,
        cause: Throwable,
    ): ParsingOutcome {
        log.error("item {} 추출 결과 기록 실패, 회수 대기", itemId, cause)
        return ParsingOutcome.NotApplied
    }

    // require·check·error 가 던지는 예외. 일시 오류와 달리 재실행해도 결과가 같음
    private fun Throwable.isInvariantViolation(): Boolean = this is IllegalArgumentException || this is IllegalStateException

    private fun recordSettled(
        itemId: Long,
        logSubject: String,
        extracted: ProductSnapshot,
        status: ItemStatus,
        started: Long,
    ): ParsingOutcome {
        val missing = extracted.takeIf { status == ItemStatus.INCOMPLETE }?.let { " missing=${ItemParsingMetrics.missingFieldsOf(it)}" }.orEmpty()
        recordResult(itemId, resultOf(status), reasonOf(status), started, "$logSubject$missing")
        return ParsingOutcome.Settled(extracted, status)
    }

    private fun failExtraction(
        itemId: Long,
        itemParseOutboxId: Long,
        logSubject: String,
        cause: Throwable,
        started: Long,
    ): ParsingOutcome {
        markError(cause)
        val reason = ItemParsingMetrics.failureReasonOf(cause)
        val outcome = fail(itemId, itemParseOutboxId, logSubject, reason, started)
        if (outcome.isApplied()) log.info("item.parse.error item={} reason={} cause={}", itemId, reason.metricLabel, cause.message)
        return outcome
    }

    private fun markError(cause: Throwable) {
        observationRegistry.currentObservation?.error(cause)
    }

    private fun fail(
        itemId: Long,
        itemParseOutboxId: Long,
        logSubject: String,
        reason: ParseFailureReason,
        started: Long,
    ): ParsingOutcome {
        if (!markFailedQuietly(itemParseOutboxId)) return ParsingOutcome.NotApplied
        recordResult(itemId, ItemParsingMetrics.RESULT_FAILED, reason.metricLabel, started, logSubject)
        return ParsingOutcome.Failed
    }

    private fun markFailedQuietly(itemParseOutboxId: Long): Boolean =
        runCatchingException { itemParsingService.markFailed(itemParseOutboxId) }
            .onFailure { e -> log.error("item parse outbox {} FAILED 전이 실패, 회수 대기", itemParseOutboxId, e) }
            .getOrDefault(false)

    private fun recordResult(
        itemId: Long,
        result: String,
        reason: String,
        started: Long,
        trailingFields: String,
    ) {
        val latencyMs = (System.nanoTime() - started) / 1_000_000
        log.info("item.parse.result item={} result={} reason={} latency={}ms {}", itemId, result, reason, latencyMs, trailingFields)
        ItemParsingMetrics.record(meterRegistry, result, reason)
    }

    private fun resultOf(status: ItemStatus): String =
        when (status) {
            ItemStatus.READY -> ItemParsingMetrics.RESULT_READY
            ItemStatus.INCOMPLETE -> ItemParsingMetrics.RESULT_INCOMPLETE
            ItemStatus.FAILED -> ItemParsingMetrics.RESULT_FAILED
            ItemStatus.PENDING, ItemStatus.PROCESSING -> error("추출 전이가 만들 수 없는 상태 $status")
        }

    private fun reasonOf(status: ItemStatus): String =
        if (status == ItemStatus.FAILED) ParseFailureReason.EXTRACT_QUALITY.metricLabel else ItemParsingMetrics.REASON_NONE

    private fun recordIdentity(
        itemId: Long,
        finalUrl: String?,
    ) {
        runCatchingException { itemIdentityRecorder.recordParsingIdentity(itemId, finalUrl) }
            .onFailure { e -> log.warn("item.identity.error item={} 정체성 기록 실패", itemId, e) }
    }

    private fun deleteRaw(imageKey: String) {
        runCatchingException { imageStorage.delete(imageKey) }
            .onFailure { e -> log.warn("raw 이미지 {} 회수 실패: {}", imageKey, e.message) }
    }

    companion object {
        private const val PARSE_OBSERVATION = "item.parse"
    }
}
