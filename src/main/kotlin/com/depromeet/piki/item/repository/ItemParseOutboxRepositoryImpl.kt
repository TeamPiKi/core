package com.depromeet.piki.item.repository

import com.depromeet.piki.item.domain.ItemParseOutbox
import com.depromeet.piki.item.domain.ParseRequester
import com.depromeet.piki.item.domain.ParseRequesterType
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Repository

@Repository
class ItemParseOutboxRepositoryImpl(
    private val itemParseOutboxJpaRepository: ItemParseOutboxJpaRepository,
) : ItemParseOutboxRepository {
    override fun save(outbox: ItemParseOutbox): ItemParseOutbox = itemParseOutboxJpaRepository.save(outbox)

    override fun findPendingForUpdate(limit: Int): List<ItemParseOutbox> =
        itemParseOutboxJpaRepository.findPendingForUpdate(Limit.of(limit))

    override fun findById(id: Long): ItemParseOutbox? = itemParseOutboxJpaRepository.findByIdAndDeletedAtIsNull(id)

    override fun findByIdForUpdate(id: Long): ItemParseOutbox? = itemParseOutboxJpaRepository.findByIdForUpdate(id)

    override fun findLatestByRequesters(
        type: ParseRequesterType,
        ids: Collection<Long>,
    ): List<ItemParseOutbox> = if (ids.isEmpty()) emptyList() else itemParseOutboxJpaRepository.findLatestByRequesters(type, ids)

    override fun findLatestByRequester(requester: ParseRequester): ItemParseOutbox? =
        findLatestByRequesters(requester.type, listOf(requester.id)).firstOrNull()

    override fun findRegisteredImageKeys(keys: Collection<String>): Set<String> =
        if (keys.isEmpty()) emptySet() else itemParseOutboxJpaRepository.findRegisteredImageKeys(keys).toSet()
}
