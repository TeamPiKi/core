package com.depromeet.piki.item.repository

import com.depromeet.piki.item.domain.ItemParseOutbox
import com.depromeet.piki.item.domain.ItemSlot
import com.depromeet.piki.item.domain.ItemSlotType
import java.time.LocalDateTime
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Repository

@Repository
class ItemParseOutboxRepositoryImpl(
    private val itemParseOutboxJpaRepository: ItemParseOutboxJpaRepository,
) : ItemParseOutboxRepository {
    override fun save(request: ItemParseOutbox): ItemParseOutbox = itemParseOutboxJpaRepository.save(request)

    override fun findById(id: Long): ItemParseOutbox? = itemParseOutboxJpaRepository.findByIdAndDeletedAtIsNull(id)

    override fun findInProgressForUpdate(id: Long): ItemParseOutbox? =
        itemParseOutboxJpaRepository.findByIdForUpdate(id)?.takeIf { it.isInProgress() }

    override fun findInProgressIdsCreatedBefore(
        before: LocalDateTime,
        limit: Int,
    ): List<Long> = itemParseOutboxJpaRepository.findInProgressIdsCreatedBefore(before, Limit.of(limit))

    override fun findLatestBySlots(
        type: ItemSlotType,
        ids: Collection<Long>,
    ): List<ItemParseOutbox> = if (ids.isEmpty()) emptyList() else itemParseOutboxJpaRepository.findLatestBySlots(type, ids)

    override fun findLatestBySlot(slot: ItemSlot): ItemParseOutbox? =
        findLatestBySlots(slot.type, listOf(slot.id)).firstOrNull()
}
