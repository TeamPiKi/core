package com.depromeet.piki.item.service

import com.depromeet.piki.common.config.AsyncConfig
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Component

// 파싱 풀에 대기실이 없어(AsyncConfig) 빈 슬롯만큼만 집음. 나머지는 아웃박스에서 대기
@Component
class ItemParsingScheduler(
    private val itemParsingService: ItemParsingService,
    private val itemParser: ItemParser,
    @Qualifier(AsyncConfig.ITEM_PARSING_EXECUTOR) private val itemParsingExecutor: ThreadPoolTaskExecutor,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = DISPATCH_INTERVAL_MS)
    fun dispatch() {
        val free = itemParsingExecutor.maxPoolSize - itemParsingExecutor.activeCount
        if (free <= 0) return
        itemParsingService.claim(free).forEach { submit(it) }
    }

    private fun submit(itemParseOutboxId: Long) {
        runCatching { itemParser.parse(itemParseOutboxId) }.onFailure { e ->
            log.warn("item parse outbox {} 파서 제출 거부, 대기로 되돌림: {}", itemParseOutboxId, e.message)
            requeueQuietly(itemParseOutboxId)
        }
    }

    // 되돌리기가 실패해도 같은 배치의 나머지 제출은 계속한다.
    private fun requeueQuietly(itemParseOutboxId: Long) {
        runCatching { itemParsingService.requeue(itemParseOutboxId) }
            .onFailure { e -> log.error("item parse outbox {} 대기로 되돌리기 실패", itemParseOutboxId, e) }
    }

    companion object {
        private const val DISPATCH_INTERVAL_MS = 1_000L
    }
}
