package com.depromeet.piki.item.service

import com.depromeet.piki.common.config.AsyncConfig
import com.depromeet.piki.item.domain.ParseAttempt
import com.depromeet.piki.item.domain.RecoveryOutcome
import com.depromeet.piki.product.service.remote.RemoteExtractionProperties
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDateTime

// 파싱 풀에 대기실이 없어(AsyncConfig) 빈 슬롯만큼만 집음. 나머지는 아웃박스에서 대기
@Component
class ItemParsingScheduler(
    private val itemParsingService: ItemParsingService,
    private val itemParser: ItemParser,
    @Qualifier(AsyncConfig.ITEM_PARSING_EXECUTOR) private val itemParsingExecutor: ThreadPoolTaskExecutor,
    private val meterRegistry: MeterRegistry,
    remoteExtractionProperties: RemoteExtractionProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        requireExtractionUnderStuck(remoteExtractionProperties)
    }

    @Scheduled(fixedDelay = DISPATCH_INTERVAL_MS)
    fun dispatch() {
        val free = itemParsingExecutor.maxPoolSize - itemParsingExecutor.activeCount
        if (free <= 0) return
        itemParsingService.claim(free).forEach { submit(it) }
    }

    // 파싱 도중 서버가 내려가거나 결과 기록이 일시 오류로 실패한 요청은 처리 중으로 남음
    @Scheduled(fixedDelay = RECOVER_INTERVAL_MS)
    fun recover() {
        itemParsingService.recoverStuck(LocalDateTime.now().minus(STUCK_AFTER), RECOVER_LIMIT).forEach { report(it) }
    }

    private fun submit(attempt: ParseAttempt) {
        runCatchingException { itemParser.parse(attempt) }.onFailure { e ->
            log.warn("item parse outbox {} 파서 제출 거부, 대기로 되돌림: {}", attempt.itemParseOutboxId, e.message)
            requeueQuietly(attempt)
        }
    }

    // 되돌리기가 실패해도 같은 배치의 나머지 제출은 계속한다.
    private fun requeueQuietly(attempt: ParseAttempt) {
        runCatchingException { itemParsingService.requeue(attempt) }
            .onFailure { e -> log.error("item parse outbox {} 대기로 되돌리기 실패", attempt.itemParseOutboxId, e) }
    }

    private fun report(recovery: StuckRecovery) {
        when (recovery.outcome) {
            RecoveryOutcome.REQUEUED -> log.warn("item.parse.recover outbox={} action=requeue", recovery.itemParseOutboxId)
            RecoveryOutcome.EXHAUSTED -> {
                val reason = ParseFailureReason.INTERNAL_ERROR.metricLabel
                log.warn("item.parse.result outbox={} result={} reason={} recovery=exhausted", recovery.itemParseOutboxId, ItemParsingMetrics.RESULT_FAILED, reason)
                ItemParsingMetrics.record(meterRegistry, ItemParsingMetrics.RESULT_FAILED, reason)
            }
        }
    }

    companion object {
        private const val DISPATCH_INTERVAL_MS = 1_000L
        private const val RECOVER_INTERVAL_MS = 30_000L
        private const val RECOVER_LIMIT = 100

        private val STUCK_AFTER: Duration = Duration.ofMinutes(2)

        // 추출 호출 중에는 행을 잠그지 않아 상한이 회수 기준보다 길면 살아 있는 워커의 요청을 회수함(read 상한은 read 한 번마다)
        fun requireExtractionUnderStuck(properties: RemoteExtractionProperties) =
            require(properties.connectTimeoutMs + properties.readTimeoutMs < STUCK_AFTER.toMillis()) {
                "connect-timeout(${properties.connectTimeoutMs}ms) + read-timeout(${properties.readTimeoutMs}ms)은 " +
                    "회수 기준(${STUCK_AFTER.toMillis()}ms)보다 작아야 한다"
            }
    }
}
