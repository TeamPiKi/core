package com.depromeet.piki.item.service

import com.depromeet.piki.common.config.AsyncConfig
import com.depromeet.piki.common.outbox.OutboxAttempt
import com.depromeet.piki.common.outbox.OutboxRecovery
import com.depromeet.piki.common.outbox.RecoveryOutcome
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Component

// 파싱 풀에 대기실이 없어(AsyncConfig) 빈 슬롯만큼만 집음. 나머지는 아웃박스에서 대기
@Component
class ItemParsingScheduler(
    private val itemParseOutboxQueue: ItemParseOutboxQueue,
    private val itemParser: ItemParser,
    @Qualifier(AsyncConfig.ITEM_PARSING_EXECUTOR) private val itemParsingExecutor: ThreadPoolTaskExecutor,
    private val itemParseResultRecorder: ItemParseResultRecorder,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = DISPATCH_INTERVAL_MS)
    fun dispatch() {
        val free = itemParsingExecutor.maxPoolSize - itemParsingExecutor.activeCount
        if (free <= 0) return
        itemParseOutboxQueue.claim(free).forEach { submit(it) }
    }

    // 파싱 도중 서버가 내려가거나 결과 기록이 일시 오류로 실패한 요청은 처리 중으로 남음
    @Scheduled(fixedDelay = RECOVER_INTERVAL_MS)
    fun recover() {
        itemParseOutboxQueue.recoverStuck(RECOVER_LIMIT).forEach { report(it) }
    }

    private fun submit(attempt: OutboxAttempt) {
        runCatchingException { itemParser.parse(attempt) }.onFailure { e ->
            log.warn("item parse outbox {} 파서 제출 거부, 대기로 되돌림: {}", attempt.outboxId, e.message)
            requeueQuietly(attempt)
        }
    }

    // 되돌리기가 실패해도 같은 배치의 나머지 제출은 계속한다.
    private fun requeueQuietly(attempt: OutboxAttempt) {
        runCatchingException { itemParseOutboxQueue.requeue(attempt) }
            .onFailure { e -> log.error("item parse outbox {} 대기로 되돌리기 실패", attempt.outboxId, e) }
    }

    private fun report(recovery: OutboxRecovery) {
        when (recovery.outcome) {
            RecoveryOutcome.REQUEUED -> log.warn("item.parse.recover outbox={} action=requeue", recovery.outboxId)
            RecoveryOutcome.EXHAUSTED -> itemParseResultRecorder.recordFailed(recovery.outboxId, ParseFailureReason.INTERNAL_ERROR, "recovery=exhausted")
        }
    }

    companion object {
        private const val DISPATCH_INTERVAL_MS = 1_000L
        private const val RECOVER_INTERVAL_MS = 30_000L
        private const val RECOVER_LIMIT = 100
    }
}
