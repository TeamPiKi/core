package com.depromeet.piki.item.repository

import com.depromeet.piki.common.outbox.OutboxRepositoryAdapter
import com.depromeet.piki.item.domain.ItemParseOutbox
import com.depromeet.piki.item.domain.ItemSlot
import com.depromeet.piki.item.domain.ItemSlotType
import org.springframework.stereotype.Repository

@Repository
class ItemParseOutboxRepositoryImpl(
    private val itemParseOutboxJpaRepository: ItemParseOutboxJpaRepository,
) : OutboxRepositoryAdapter<ItemParseOutbox>(itemParseOutboxJpaRepository),
    ItemParseOutboxRepository {
    override fun findById(id: Long): ItemParseOutbox? = itemParseOutboxJpaRepository.findByIdAndDeletedAtIsNull(id)

    override fun findLatestBySlots(
        type: ItemSlotType,
        ids: Collection<Long>,
    ): List<ItemParseOutbox> = if (ids.isEmpty()) emptyList() else itemParseOutboxJpaRepository.findLatestBySlots(type, ids)

    override fun findLatestBySlot(slot: ItemSlot): ItemParseOutbox? =
        findLatestBySlots(slot.type, listOf(slot.id)).firstOrNull()
}
