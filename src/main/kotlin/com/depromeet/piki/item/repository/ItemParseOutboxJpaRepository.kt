package com.depromeet.piki.item.repository

import com.depromeet.piki.common.outbox.OutboxJpaRepository
import com.depromeet.piki.item.domain.ItemParseOutbox
import com.depromeet.piki.item.domain.ItemSlotType
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ItemParseOutboxJpaRepository : OutboxJpaRepository<ItemParseOutbox> {
    fun findByIdAndDeletedAtIsNull(id: Long): ItemParseOutbox?

    @Query(
        "select o from ItemParseOutbox o where o.slotType = :type and o.slotId in :ids and o.deletedAt is null " +
            "and o.id = (select max(o2.id) from ItemParseOutbox o2 where o2.slotType = o.slotType " +
            "and o2.slotId = o.slotId and o2.deletedAt is null)",
    )
    fun findLatestBySlots(
        @Param("type") type: ItemSlotType,
        @Param("ids") ids: Collection<Long>,
    ): List<ItemParseOutbox>
}
