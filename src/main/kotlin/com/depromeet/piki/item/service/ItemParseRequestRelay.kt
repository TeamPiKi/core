package com.depromeet.piki.item.service

import com.depromeet.piki.common.event.AtLeastOnce
import com.depromeet.piki.item.event.ItemParseRequested
import com.depromeet.piki.item.repository.ItemParseOutboxRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class ItemParseRequestRelay(
    private val itemParseOutboxRepository: ItemParseOutboxRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @AtLeastOnce
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, id = LISTENER_ID)
    fun on(event: ItemParseRequested) {
        val request = itemParseOutboxRepository.findById(event.requestId) ?: return
        if (!request.isInProgress()) return
        log.debug("item.parse.relay request={}", event.requestId)
    }

    companion object {
        // 발행 기록이 이 값으로 리스너를 다시 찾음. 바꾸면 남아 있던 기록이 갈 곳을 잃음
        const val LISTENER_ID = "item-parse-request-relay"
    }
}
