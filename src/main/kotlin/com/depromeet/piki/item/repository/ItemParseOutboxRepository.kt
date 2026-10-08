package com.depromeet.piki.item.repository

import com.depromeet.piki.item.domain.ItemParseOutbox
import com.depromeet.piki.item.domain.ParseRequester
import com.depromeet.piki.item.domain.ParseRequesterType

interface ItemParseOutboxRepository {
    fun save(outbox: ItemParseOutbox): ItemParseOutbox

    fun findPendingForUpdate(limit: Int): List<ItemParseOutbox>

    fun findById(id: Long): ItemParseOutbox?

    fun findByIdForUpdate(id: Long): ItemParseOutbox?

    fun findLatestByRequesters(
        type: ParseRequesterType,
        ids: Collection<Long>,
    ): List<ItemParseOutbox>

    fun findLatestByRequester(requester: ParseRequester): ItemParseOutbox?

    fun findRegisteredImageKeys(keys: Collection<String>): Set<String>
}
