package com.depromeet.piki.item.repository

import com.depromeet.piki.item.domain.ItemParseOutbox

interface ItemParseOutboxRepository {
    fun save(outbox: ItemParseOutbox): ItemParseOutbox

    fun findPendingForUpdate(limit: Int): List<ItemParseOutbox>

    fun findById(id: Long): ItemParseOutbox?

    fun findByIdForUpdate(id: Long): ItemParseOutbox?
}
