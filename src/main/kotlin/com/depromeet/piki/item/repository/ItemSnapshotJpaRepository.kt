package com.depromeet.piki.item.repository

import com.depromeet.piki.item.domain.ItemSnapshot
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface ItemSnapshotJpaRepository : JpaRepository<ItemSnapshot, Long> {
    @Query(
        "select s from ItemSnapshot s where s.itemId = :itemId " +
            "and s.name is not null and s.price is not null and s.imageUrl is not null " +
            "and s.deletedAt is null " +
            "and (s.method is not null or s.userInput = true) " +
            "order by s.id desc",
    )
    fun findPriceHistoryByItemId(
        @Param("itemId") itemId: Long,
        pageable: Pageable,
    ): List<ItemSnapshot>

    fun findByIdAndDeletedAtIsNull(id: Long): ItemSnapshot?

    fun findByIdInAndDeletedAtIsNull(ids: Collection<Long>): List<ItemSnapshot>

    fun findByItemIdInAndDeletedAtIsNullOrderByIdAsc(itemIds: Collection<Long>): List<ItemSnapshot>

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select s from ItemSnapshot s where s.itemId in :itemIds and s.deletedAt is null order by s.id")
    fun findByItemIdsForShare(
        @Param("itemIds") itemIds: Collection<Long>,
    ): List<ItemSnapshot>

    fun findByItemIdInAndCreatedAtLessThanEqualAndDeletedAtIsNullOrderByIdAsc(
        itemIds: Collection<Long>,
        at: LocalDateTime,
    ): List<ItemSnapshot>
}
