package com.depromeet.piki.item.repository

import com.depromeet.piki.item.domain.ItemSnapshot
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ItemSnapshotJpaRepository : JpaRepository<ItemSnapshot, Long> {
    fun findFirstByItemIdAndDeletedAtIsNullOrderByIdDesc(itemId: Long): ItemSnapshot?

    @Query(
        "select s from ItemSnapshot s where s.itemId = :itemId " +
            "and s.status = com.depromeet.piki.item.domain.ItemStatus.READY " +
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

    @Query(
        "select s from ItemSnapshot s where s.itemId = :itemId and s.status in (com.depromeet.piki.item.domain.ItemStatus.PENDING, com.depromeet.piki.item.domain.ItemStatus.PROCESSING) and s.deletedAt is null order by s.id desc limit 1",
    )
    fun findLatestInProgressByItemId(
        @Param("itemId") itemId: Long,
    ): ItemSnapshot?

    @Query(
        "select s from ItemSnapshot s where s.itemId = :itemId and s.status = com.depromeet.piki.item.domain.ItemStatus.READY and s.source in (com.depromeet.piki.item.domain.ItemSnapshotSource.SERVER, com.depromeet.piki.item.domain.ItemSnapshotSource.SERVER_LLM) and s.deletedAt is null order by s.id desc limit 1",
    )
    fun findLatestMachineReadyByItemId(
        @Param("itemId") itemId: Long,
    ): ItemSnapshot?

    @Modifying
    @Query(
        value = "UPDATE item_snapshots SET item_id = :toItemId, updated_at = NOW(6) WHERE item_id = :fromItemId",
        nativeQuery = true,
    )
    fun reparentAll(
        @Param("fromItemId") fromItemId: Long,
        @Param("toItemId") toItemId: Long,
    ): Int
}
