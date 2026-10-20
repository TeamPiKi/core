package com.depromeet.piki.item.repository

import com.depromeet.piki.item.domain.ItemParseOutbox
import com.depromeet.piki.item.domain.ItemSlot
import com.depromeet.piki.item.domain.ItemSlotType

interface ItemParseOutboxRepository {
    fun save(outbox: ItemParseOutbox): ItemParseOutbox

    fun findPendingForUpdate(limit: Int): List<ItemParseOutbox>

    fun findById(id: Long): ItemParseOutbox?

    fun findByIdForUpdate(id: Long): ItemParseOutbox?

    fun findLatestBySlots(
        type: ItemSlotType,
        ids: Collection<Long>,
    ): List<ItemParseOutbox>

    fun findLatestBySlot(slot: ItemSlot): ItemParseOutbox?
}
