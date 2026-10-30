package com.depromeet.piki.item.service

import com.depromeet.piki.common.config.AsyncConfig
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
        requireExtractionUnderStuck(remoteExtractionProperties.connectTimeoutMs, remoteExtractionProperties.readTimeoutMs)
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

    private fun submit(itemParseOutboxId: Long) {
        runCatchingException { itemParser.parse(itemParseOutboxId) }.onFailure { e ->
            log.warn("item parse outbox {} 파서 제출 거부, 대기로 되돌림: {}", itemParseOutboxId, e.message)
            requeueQuietly(itemParseOutboxId)
        }
    }

    // 되돌리기가 실패해도 같은 배치의 나머지 제출은 계속한다.
    private fun requeueQuietly(itemParseOutboxId: Long) {
        runCatchingException { itemParsingService.requeue(itemParseOutboxId) }
            .onFailure { e -> log.error("item parse outbox {} 대기로 되돌리기 실패", itemParseOutboxId, e) }
    }

    private fun report(recovery: StuckRecovery) {
        when (recovery) {
            is StuckRecovery.Requeued -> log.warn("item.parse.recover outbox={} action=requeue", recovery.itemParseOutboxId)
            is StuckRecovery.Failed -> {
                log.warn("item.parse.recover outbox={} action=fail 회수한 뒤 다시 멈춤", recovery.itemParseOutboxId)
                ItemParsingMetrics.record(meterRegistry, ItemParsingMetrics.RESULT_FAILED, ParseFailureReason.INTERNAL_ERROR.metricLabel)
            }
        }
    }

    companion object {
        private const val DISPATCH_INTERVAL_MS = 1_000L
        private const val RECOVER_INTERVAL_MS = 30_000L
        private const val RECOVER_LIMIT = 100

        // 한 번의 파싱은 추출 읽기 상한 안에 끝나므로 그보다 오래 처리 중이면 워커가 없는 것
        private val STUCK_AFTER: Duration = Duration.ofMinutes(2)

        // 추출 호출 동안은 행을 잠그지 않아 회수가 집을 수 있음. 호출 상한이 더 길면 살아 있는 워커의 요청을 회수함
        // read 상한은 read 한 번마다라 조금씩 오는 응답은 넘을 수 있음
        fun requireExtractionUnderStuck(
            connectTimeoutMs: Int,
            readTimeoutMs: Int,
        ) = require(connectTimeoutMs + readTimeoutMs < STUCK_AFTER.toMillis()) {
            "connect-timeout(${connectTimeoutMs}ms) + read-timeout(${readTimeoutMs}ms)은 회수 기준(${STUCK_AFTER.toMillis()}ms)보다 작아야 한다"
        }
    }
}
