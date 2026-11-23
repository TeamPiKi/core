package com.depromeet.piki.item.repository

import com.depromeet.piki.common.outbox.OutboxJpaRepository
import com.depromeet.piki.item.domain.ItemParseOutbox
import com.depromeet.piki.item.domain.ItemSlotType
import java.time.LocalDateTime
import org.springframework.data.domain.Limit
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

    @Query(
        "select o.id from ItemParseOutbox o where o.status in (com.depromeet.piki.common.outbox.OutboxStatus.PENDING, " +
            "com.depromeet.piki.common.outbox.OutboxStatus.PROCESSING) and o.createdAt < :before and o.deletedAt is null " +
            "order by o.createdAt asc, o.id asc",
    )
    fun findInProgressIdsCreatedBefore(
        @Param("before") before: LocalDateTime,
        limit: Limit,
    ): List<Long>
}
