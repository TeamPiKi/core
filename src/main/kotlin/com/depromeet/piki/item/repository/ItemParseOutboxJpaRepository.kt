package com.depromeet.piki.item.repository

import com.depromeet.piki.item.domain.ItemParseOutbox
import com.depromeet.piki.item.domain.ParseRequesterType
import jakarta.persistence.LockModeType
import jakarta.persistence.QueryHint
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.QueryHints
import org.springframework.data.repository.query.Param

// Hibernate LockOptions.SKIP_LOCKED. 동시에 집는 디스패처가 서로를 기다리지 않게 한다(#770 교착).
private const val SKIP_LOCKED = "-2"

interface ItemParseOutboxJpaRepository : JpaRepository<ItemParseOutbox, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(QueryHint(name = "jakarta.persistence.lock.timeout", value = SKIP_LOCKED))
    @Query(
        "select o from ItemParseOutbox o where o.status = com.depromeet.piki.item.domain.ItemParseOutboxStatus.PENDING " +
            "and o.deletedAt is null order by o.createdAt asc, o.id asc",
    )
    fun findPendingForUpdate(limit: Limit): List<ItemParseOutbox>

    fun findByIdAndDeletedAtIsNull(id: Long): ItemParseOutbox?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from ItemParseOutbox o where o.id = :id and o.deletedAt is null")
    fun findByIdForUpdate(
        @Param("id") id: Long,
    ): ItemParseOutbox?

    @Query(
        "select o from ItemParseOutbox o where o.requesterType = :type and o.requesterId in :ids and o.deletedAt is null " +
            "and o.id = (select max(o2.id) from ItemParseOutbox o2 where o2.requesterType = o.requesterType " +
            "and o2.requesterId = o.requesterId and o2.deletedAt is null)",
    )
    fun findLatestByRequesters(
        @Param("type") type: ParseRequesterType,
        @Param("ids") ids: Collection<Long>,
    ): List<ItemParseOutbox>
}
