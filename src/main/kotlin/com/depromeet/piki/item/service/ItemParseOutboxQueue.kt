package com.depromeet.piki.item.service

import com.depromeet.piki.common.outbox.OutboxPolicy
import com.depromeet.piki.common.outbox.OutboxQueue
import com.depromeet.piki.item.domain.ItemParseOutbox
import com.depromeet.piki.item.repository.ItemParseOutboxJpaRepository
import com.depromeet.piki.product.service.remote.RemoteExtractionProperties
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration

@Component
class ItemParseOutboxQueue(
    jpaRepository: ItemParseOutboxJpaRepository,
    transactionManager: PlatformTransactionManager,
    remoteExtractionProperties: RemoteExtractionProperties,
    private val itemParsingService: ItemParsingService,
) : OutboxQueue<ItemParseOutbox>(jpaRepository, transactionManager, policyOf(remoteExtractionProperties)) {
    override fun onExhausted(outbox: ItemParseOutbox) = itemParsingService.notifyRecoveryExhausted(outbox)

    companion object {
        // read 상한은 read 한 번마다라 추출 전체 시간이 아님
        fun policyOf(properties: RemoteExtractionProperties): OutboxPolicy =
            OutboxPolicy(
                stuckAfter = Duration.ofMinutes(2),
                maxRecoveries = 1,
                processingLimit = Duration.ofMillis(properties.connectTimeoutMs.toLong() + properties.readTimeoutMs),
            )
    }
}
