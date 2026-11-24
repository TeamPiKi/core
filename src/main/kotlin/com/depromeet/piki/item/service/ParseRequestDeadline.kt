package com.depromeet.piki.item.service

import com.depromeet.piki.contracts.extraction.v1.ExtractionProto
import com.depromeet.piki.contracts.extraction.v1.ParseRequest
import com.depromeet.piki.item.repository.ItemParseOutboxRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDateTime

@Component
class ParseRequestDeadline(
    private val itemParseOutboxRepository: ItemParseOutboxRepository,
    private val itemParsingService: ItemParsingService,
    private val itemParseResultRecorder: ItemParseResultRecorder,
    @Value("\${spring.modulith.events.staleness.published}") stalePublishedAfter: Duration,
    @Value("\${spring.modulith.events.staleness.check-interval}") staleCheckInterval: Duration,
    @Value("\${event-publication.resubmit-interval}") resubmitInterval: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        // 기한이 정상 경로의 최악보다 짧으면 처리 중인 요청을 실패로 닫음. 식의 정본은 extraction-api.md 8절
        val firstSendDelay = stalePublishedAfter + staleCheckInterval + resubmitInterval
        val untilRedelivery = REDELIVERY_AFTER + REDELIVERY_SCAN_INTERVAL
        val worstCase = firstSendDelay + untilRedelivery.multipliedBy(MAX_DELIVERIES - 1L) + PROCESSING_TIMEOUT.multipliedBy(MAX_DELIVERIES.toLong())
        check(DEADLINE > worstCase) { "계약의 result_deadline_seconds($DEADLINE)가 정상 경로의 최악($worstCase)보다 커야 한다" }
    }

    @Scheduled(fixedDelay = CHECK_INTERVAL_MS)
    fun closeOverdue() = closeOverdue(DEADLINE)

    fun closeOverdue(deadline: Duration) {
        itemParseOutboxRepository
            .findInProgressIdsCreatedBefore(LocalDateTime.now().minus(deadline), LIMIT)
            .forEach(::close)
    }

    private fun close(requestId: Long) {
        runCatchingException { itemParsingService.expire(requestId) }
            .onSuccess { closed -> if (closed) itemParseResultRecorder.recordFailed(requestId, ParseFailureReason.INTERNAL_ERROR, "deadline=exceeded") }
            .onFailure { e -> log.error("item.parse.deadline request={} 기한이 지난 요청을 닫지 못함", requestId, e) }
    }

    companion object {
        private const val CHECK_INTERVAL_MS = 60_000L
        private const val LIMIT = 100

        private val REQUEST_OPTIONS = ParseRequest.getDescriptor().options
        private val DEADLINE = Duration.ofSeconds(REQUEST_OPTIONS.getExtension(ExtractionProto.resultDeadlineSeconds).toLong())
        private val REDELIVERY_AFTER = Duration.ofSeconds(REQUEST_OPTIONS.getExtension(ExtractionProto.redeliveryAfterSeconds).toLong())
        private val MAX_DELIVERIES = REQUEST_OPTIONS.getExtension(ExtractionProto.maxDeliveries)
        private val REDELIVERY_SCAN_INTERVAL = Duration.ofSeconds(REQUEST_OPTIONS.getExtension(ExtractionProto.redeliveryScanIntervalSeconds).toLong())
        private val PROCESSING_TIMEOUT = Duration.ofSeconds(REQUEST_OPTIONS.getExtension(ExtractionProto.processingTimeoutSeconds).toLong())
    }
}
