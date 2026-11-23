package com.depromeet.piki.item.repository

import com.depromeet.piki.common.outbox.OutboxRepository
import com.depromeet.piki.item.domain.ItemParseOutbox
import com.depromeet.piki.item.domain.ItemSlot
import com.depromeet.piki.item.domain.ItemSlotType
import java.time.LocalDateTime

interface ItemParseOutboxRepository : OutboxRepository<ItemParseOutbox> {
    fun findById(id: Long): ItemParseOutbox?

    fun findInProgressForUpdate(id: Long): ItemParseOutbox?

    fun findInProgressIdsCreatedBefore(
        before: LocalDateTime,
        limit: Int,
    ): List<Long>

    fun findLatestBySlots(
        type: ItemSlotType,
        ids: Collection<Long>,
    ): List<ItemParseOutbox>

    fun findLatestBySlot(slot: ItemSlot): ItemParseOutbox?
}
