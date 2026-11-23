package com.depromeet.piki.item.service

import com.depromeet.piki.item.repository.ItemParseOutboxRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDateTime

@Component
class ParseRequestDeadline(
    private val itemParseOutboxRepository: ItemParseOutboxRepository,
    private val itemParsingService: ItemParsingService,
    private val itemParseResultRecorder: ItemParseResultRecorder,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = CHECK_INTERVAL_MS)
    fun closeOverdue() = closeOverdue(DEADLINE)

    fun closeOverdue(deadline: Duration) {
        itemParseOutboxRepository
            .findInProgressIdsCreatedBefore(LocalDateTime.now().minus(deadline), LIMIT)
            .forEach(::close)
    }

    private fun close(requestId: Long) {
        runCatchingException { itemParsingService.applyFailed(requestId) }
            .onSuccess { closed -> if (closed) itemParseResultRecorder.recordFailed(requestId, ParseFailureReason.INTERNAL_ERROR, "deadline=exceeded") }
            .onFailure { e -> log.error("item.parse.deadline request={} 기한이 지난 요청을 닫지 못함", requestId, e) }
    }

    companion object {
        private const val CHECK_INTERVAL_MS = 60_000L
        private const val LIMIT = 100
        private val DEADLINE = Duration.ofMinutes(7)
    }
}
