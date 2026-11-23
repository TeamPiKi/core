package com.depromeet.piki.item.service

import com.depromeet.piki.item.domain.ItemParseOutbox
import com.depromeet.piki.item.domain.ItemSlot
import com.depromeet.piki.item.domain.ParseInput
import com.depromeet.piki.item.domain.ParsePurpose
import com.depromeet.piki.item.event.ItemParseRequested
import com.depromeet.piki.item.repository.ItemParseOutboxRepository
import com.depromeet.piki.product.routing.DomainAccessPolicy
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

@Component
class ParsingEnqueuer(
    private val itemParseOutboxRepository: ItemParseOutboxRepository,
    private val itemParsingService: ItemParsingService,
    private val itemParseResultRecorder: ItemParseResultRecorder,
    private val accessPolicy: DomainAccessPolicy,
    private val eventPublisher: ApplicationEventPublisher,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun enqueue(
        slot: ItemSlot,
        purpose: ParsePurpose,
        input: ParseInput,
    ): ItemParseOutbox {
        val request = itemParseOutboxRepository.save(ItemParseOutbox.of(slot, purpose, input))
        if (blocked(input)) return closeBlocked(request)
        eventPublisher.publishEvent(ItemParseRequested.of(request))
        return request
    }

    private fun blocked(input: ParseInput): Boolean = input is ParseInput.Link && accessPolicy.blocked(input.link)

    // 등록 경계도 막지만 이미 담긴 상품의 새로고침은 여기가 유일한 출구. 요청을 내보내지 않고 닫음
    private fun closeBlocked(request: ItemParseOutbox): ItemParseOutbox {
        itemParsingService.applyFailed(request.getId())
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() {
                    itemParseResultRecorder.recordFailed(request.getId(), ParseFailureReason.BLOCKED, "blocked=input")
                }
            },
        )
        return request
    }
}
