package com.depromeet.piki.item.service

import com.depromeet.piki.common.outbox.OutboxAttempt
import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemParseOutbox
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.domain.ParseInput
import com.depromeet.piki.item.event.ItemParsingCompleted
import com.depromeet.piki.item.event.ItemParsingFailed
import com.depromeet.piki.item.event.ItemParsingIncomplete
import com.depromeet.piki.item.repository.ItemParseOutboxRepository
import com.depromeet.piki.item.repository.ItemRepository
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.product.service.ProductSnapshot
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class ItemParsingService(
    private val itemRepository: ItemRepository,
    private val itemSnapshotRepository: ItemSnapshotRepository,
    private val itemParseOutboxRepository: ItemParseOutboxRepository,
    private val itemResolver: ItemResolver,
    private val itemSlots: ItemSlots,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun inputOf(itemParseOutboxId: Long): ParseInput =
        itemParseOutboxRepository.findById(itemParseOutboxId)?.input ?: error("item parse outbox $itemParseOutboxId 이 없다")

    @Transactional
    fun markExtracted(
        attempt: OutboxAttempt,
        extracted: ProductSnapshot,
    ): ExtractionSettlement {
        val outbox = itemParseOutboxRepository.findHeldForUpdate(attempt) ?: return ExtractionSettlement.Superseded
        return settleExtracted(outbox, extracted, Closing({ outbox.succeed(attempt) }, { outbox.fail(attempt) }))
    }

    @Transactional
    fun markFailed(attempt: OutboxAttempt): Boolean {
        val outbox = itemParseOutboxRepository.findHeldForUpdate(attempt) ?: return false
        settleFailed(outbox, Closing({ outbox.succeed(attempt) }, { outbox.fail(attempt) }))
        return true
    }

    @Transactional
    fun applyExtracted(
        requestId: Long,
        extracted: ProductSnapshot,
    ): ExtractionSettlement {
        val request = itemParseOutboxRepository.findInProgressForUpdate(requestId) ?: return ExtractionSettlement.Superseded
        return settleExtracted(request, extracted, Closing(request::succeed, request::fail))
    }

    @Transactional
    fun applyFailed(requestId: Long): Boolean {
        val request = itemParseOutboxRepository.findInProgressForUpdate(requestId) ?: return false
        settleFailed(request, Closing(request::succeed, request::fail))
        return true
    }

    private fun settleExtracted(
        outbox: ItemParseOutbox,
        extracted: ProductSnapshot,
        closing: Closing,
    ): ExtractionSettlement {
        val slot =
            when (val state = itemSlots.find(outbox.slot)) {
                is ItemSlotState.Present -> state
                ItemSlotState.Gone -> return discard(closing)
            }
        if (!ItemSnapshot.accepts(extracted)) {
            closeAfterExtraction(outbox, closing, slot)
            return ExtractionSettlement.Applied(ItemStatus.FAILED)
        }
        val item = itemOf(outbox, slot, extracted.finalUrl)
        val version = itemSnapshotRepository.save(ItemSnapshot.extracted(item.getId(), extracted, slot.owner))
        val fill = itemSlots.fill(outbox.slot, item.getId())
        closing.succeed()
        when (fill) {
            ItemSlotFill.Filled -> eventPublisher.publishEvent(parsingFact(outbox, slot, version))
            // 합침 사실은 ItemSlots 가 발행한다
            is ItemSlotFill.AlreadyHeld -> Unit
        }
        return ExtractionSettlement.Applied(if (version.isReady()) ItemStatus.READY else ItemStatus.INCOMPLETE)
    }

    private fun settleFailed(
        outbox: ItemParseOutbox,
        closing: Closing,
    ) {
        when (val state = itemSlots.find(outbox.slot)) {
            is ItemSlotState.Present -> closeAfterExtraction(outbox, closing, state)
            ItemSlotState.Gone -> discard(closing)
        }
    }

    // 회수 상한 실패는 원인이 서버 중단이라 정체 URL 실패 관측(logRefreshFailure)에 남기지 않음
    @Transactional(propagation = Propagation.MANDATORY)
    fun notifyRecoveryExhausted(outbox: ItemParseOutbox) {
        when (val state = itemSlots.find(outbox.slot)) {
            is ItemSlotState.Present -> publishFailed(outbox, state)
            ItemSlotState.Gone -> Unit
        }
    }

    // 탈퇴로 슬롯 행이 하드 삭제되면 결과를 받을 곳이 없어 알림 없이 닫는다
    private fun discard(closing: Closing): ExtractionSettlement.Discarded {
        closing.fail()
        return ExtractionSettlement.Discarded
    }

    // 아이템 행을 잠가 토너먼트 시작과 버전 생성을 직렬화한다
    private fun itemOf(
        outbox: ItemParseOutbox,
        slot: ItemSlotState.Present,
        finalUrl: ProductLink?,
    ): Item {
        val resolvedId = slot.itemId ?: itemResolver.resolve(outbox.input, finalUrl).getId()
        val item = itemRepository.findByIdForUpdate(resolvedId) ?: error("item $resolvedId 이 없다")
        slot.itemId?.let { itemResolver.warnIfDrifted(item, finalUrl) }
        return item
    }

    private fun closeAfterExtraction(
        outbox: ItemParseOutbox,
        closing: Closing,
        slot: ItemSlotState.Present,
    ) {
        closing.fail()
        publishFailed(outbox, slot)
        if (outbox.isRefresh()) logRefreshFailure(outbox, slot)
    }

    private fun publishFailed(
        outbox: ItemParseOutbox,
        slot: ItemSlotState.Present,
    ) {
        eventPublisher.publishEvent(
            ItemParsingFailed(
                slot = outbox.slot,
                owner = slot.owner,
                refresh = outbox.isRefresh(),
            ),
        )
    }

    // 정체 URL 은 리다이렉트의 끝이라 앞선 홉이 만든 쿠키·상태 없이는 안 열리는 페이지일 수 있다. 대응 코드를 두기 전에 실제로 생기는지 본다
    private fun logRefreshFailure(
        outbox: ItemParseOutbox,
        slot: ItemSlotState.Present,
    ) {
        log.error(
            "item.refresh.failed outbox={} item={} url=\"{}\" 정체 URL 로 다시 가져오지 못함",
            outbox.getId(),
            slot.itemId,
            outbox.inputValue,
        )
    }

    private fun parsingFact(
        outbox: ItemParseOutbox,
        slot: ItemSlotState.Present,
        version: ItemSnapshot,
    ): Any {
        val refresh = outbox.isRefresh()
        return if (version.isReady()) {
            ItemParsingCompleted(outbox.slot, slot.owner, version.itemId, version.getId(), refresh)
        } else {
            ItemParsingIncomplete(outbox.slot, slot.owner, version.itemId, version.getId(), refresh)
        }
    }

    private class Closing(
        val succeed: () -> Unit,
        val fail: () -> Unit,
    )
}
