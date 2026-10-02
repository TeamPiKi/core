package com.depromeet.piki.item.repository

import com.depromeet.piki.item.domain.ItemParseOutbox
import com.depromeet.piki.item.domain.ItemParseOutboxStatus
import org.springframework.data.domain.Limit
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Repository

@Repository
class ItemParseOutboxRepositoryImpl(
    private val itemParseOutboxJpaRepository: ItemParseOutboxJpaRepository,
) : ItemParseOutboxRepository {
    override fun save(outbox: ItemParseOutbox): ItemParseOutbox = itemParseOutboxJpaRepository.save(outbox)

    override fun findPendingForUpdate(limit: Int): List<ItemParseOutbox> =
        itemParseOutboxJpaRepository.findByStatusForUpdate(ItemParseOutboxStatus.PENDING, Limit.of(limit))

    override fun findById(id: Long): ItemParseOutbox? = itemParseOutboxJpaRepository.findByIdOrNull(id)

    override fun findByIdForUpdate(id: Long): ItemParseOutbox? = itemParseOutboxJpaRepository.findByIdForUpdate(id)
}
