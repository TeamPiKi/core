package com.depromeet.piki.item.service

import com.depromeet.piki.item.event.ItemParseRequested
import com.depromeet.piki.item.repository.ItemParseOutboxRepository
import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

@Component
class ItemParseRequestRelay(
    private val itemParseOutboxRepository: ItemParseOutboxRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @ApplicationModuleListener(id = LISTENER_ID, readOnlyTransaction = true)
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
