package com.depromeet.piki.item.repository

import com.depromeet.piki.item.domain.ItemSnapshot
import java.time.LocalDateTime
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ItemSnapshotJpaRepository : JpaRepository<ItemSnapshot, Long> {
    @Query(
        "select s from ItemSnapshot s where s.itemId = :itemId " +
            "and s.name is not null and s.price is not null and s.imageUrl is not null " +
            "and s.deletedAt is null " +
            "and s.source is not null " +
            "order by s.id desc",
    )
    fun findPriceHistoryByItemId(
        @Param("itemId") itemId: Long,
        pageable: Pageable,
    ): List<ItemSnapshot>

    fun findByIdAndDeletedAtIsNull(id: Long): ItemSnapshot?

    fun findByIdInAndDeletedAtIsNull(ids: Collection<Long>): List<ItemSnapshot>

    fun findByItemIdInAndDeletedAtIsNullOrderByIdAsc(itemIds: Collection<Long>): List<ItemSnapshot>

    fun findByItemIdInAndCreatedAtLessThanEqualAndDeletedAtIsNullOrderByIdAsc(
        itemIds: Collection<Long>,
        at: LocalDateTime,
    ): List<ItemSnapshot>
}
