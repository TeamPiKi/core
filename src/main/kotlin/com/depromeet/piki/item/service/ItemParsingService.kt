package com.depromeet.piki.item.service

import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemParseOutbox
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.event.ItemParsingCompleted
import com.depromeet.piki.item.event.ItemParsingFailed
import com.depromeet.piki.item.event.ItemParsingIncomplete
import com.depromeet.piki.item.repository.ItemParseOutboxRepository
import com.depromeet.piki.item.repository.ItemRepository
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import com.depromeet.piki.product.service.ProductSnapshot
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Service
class ItemParsingService(
    private val itemRepository: ItemRepository,
    private val itemSnapshotRepository: ItemSnapshotRepository,
    private val itemParseOutboxRepository: ItemParseOutboxRepository,
    private val eventPublisher: ApplicationEventPublisher,
) {
    // RR 이면 대기열이 limit 보다 적을 때 끝 갭이 잠겨 새 PENDING insert 막힘
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun claim(limit: Int): List<Long> =
        itemParseOutboxRepository.findPendingForUpdate(limit).map { outbox ->
            outbox.claim()
            outbox.getId()
        }

    @Transactional(readOnly = true)
    fun itemToParse(itemParseOutboxId: Long): Item? {
        val outbox = itemParseOutboxRepository.findById(itemParseOutboxId) ?: return null
        val snapshot = itemSnapshotRepository.findById(outbox.itemSnapshotId) ?: return null
        return itemRepository.findById(snapshot.itemId)
    }

    // 이미 끝난 행이면 null 을 돌려주고 아무것도 쓰지 않는다.
    @Transactional
    fun markExtracted(
        itemParseOutboxId: Long,
        extracted: ProductSnapshot,
    ): ItemStatus? {
        val outbox = findProcessingForUpdate(itemParseOutboxId) ?: return null
        val target = snapshotOf(outbox)
        val status = target.markExtracted(extracted)
        if (status == ItemStatus.FAILED) outbox.fail() else outbox.succeed()
        eventPublisher.publishEvent(parsingFact(status, target))
        return status
    }

    // 버전이 없거나 이미 끝났어도 아웃박스는 닫음. 남겨 두면 PROCESSING 으로 영영 멈춤
    @Transactional
    fun markFailed(itemParseOutboxId: Long): Boolean {
        val outbox = findProcessingForUpdate(itemParseOutboxId) ?: return false
        val snapshot = itemSnapshotRepository.findById(outbox.itemSnapshotId)
        snapshot?.takeIf { it.isInProgress() }?.markFailed()
        outbox.fail()
        snapshot?.let { eventPublisher.publishEvent(ItemParsingFailed(it.itemId, it.getId())) }
        return true
    }

    @Transactional
    fun requeue(itemParseOutboxId: Long) {
        findProcessingForUpdate(itemParseOutboxId)?.requeue()
    }

    private fun findProcessingForUpdate(itemParseOutboxId: Long): ItemParseOutbox? =
        itemParseOutboxRepository.findByIdForUpdate(itemParseOutboxId)?.takeIf { it.isProcessing() }

    private fun snapshotOf(outbox: ItemParseOutbox): ItemSnapshot =
        itemSnapshotRepository.findById(outbox.itemSnapshotId)
            ?: error("item parse outbox ${outbox.getId()} 의 snapshot ${outbox.itemSnapshotId} 이 없다")

    private fun parsingFact(
        status: ItemStatus,
        target: ItemSnapshot,
    ): Any =
        when (status) {
            ItemStatus.READY -> ItemParsingCompleted(target.itemId, target.getId())
            ItemStatus.INCOMPLETE -> ItemParsingIncomplete(target.itemId, target.getId())
            ItemStatus.FAILED -> ItemParsingFailed(target.itemId, target.getId())
            ItemStatus.PENDING, ItemStatus.PROCESSING -> error("추출 전이가 만들 수 없는 상태 $status")
        }
}
