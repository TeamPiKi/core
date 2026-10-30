package com.depromeet.piki.item.repository

import com.depromeet.piki.item.domain.ItemParseOutbox
import com.depromeet.piki.item.domain.ItemSlot
import com.depromeet.piki.item.domain.ItemSlotType
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
class ItemParseOutboxRepositoryImpl(
    private val itemParseOutboxJpaRepository: ItemParseOutboxJpaRepository,
) : ItemParseOutboxRepository {
    override fun save(outbox: ItemParseOutbox): ItemParseOutbox = itemParseOutboxJpaRepository.save(outbox)

    override fun findPendingForUpdate(limit: Int): List<ItemParseOutbox> =
        itemParseOutboxJpaRepository.findPendingForUpdate(Limit.of(limit))

    override fun findStuckForUpdate(
        claimedBefore: LocalDateTime,
        limit: Int,
    ): List<ItemParseOutbox> = itemParseOutboxJpaRepository.findStuckForUpdate(claimedBefore, Limit.of(limit))

    override fun findById(id: Long): ItemParseOutbox? = itemParseOutboxJpaRepository.findByIdAndDeletedAtIsNull(id)

    override fun findByIdForUpdate(id: Long): ItemParseOutbox? = itemParseOutboxJpaRepository.findByIdForUpdate(id)

    override fun findLatestBySlots(
        type: ItemSlotType,
        ids: Collection<Long>,
    ): List<ItemParseOutbox> = if (ids.isEmpty()) emptyList() else itemParseOutboxJpaRepository.findLatestBySlots(type, ids)

    override fun findLatestBySlot(slot: ItemSlot): ItemParseOutbox? =
        findLatestBySlots(slot.type, listOf(slot.id)).firstOrNull()
}
