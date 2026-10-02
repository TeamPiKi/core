package com.depromeet.piki.item.repository

import com.depromeet.piki.item.domain.ItemSnapshot
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository

@Repository
class ItemSnapshotRepositoryImpl(
    private val itemSnapshotJpaRepository: ItemSnapshotJpaRepository,
) : ItemSnapshotRepository {
    override fun save(snapshot: ItemSnapshot): ItemSnapshot = itemSnapshotJpaRepository.save(snapshot)


    override fun findLatestInProgressByItemId(itemId: Long): ItemSnapshot? = itemSnapshotJpaRepository.findLatestInProgressByItemId(itemId)

    override fun findLatestMachineReadyByItemId(itemId: Long): ItemSnapshot? = itemSnapshotJpaRepository.findLatestMachineReadyByItemId(itemId)

    override fun findAllByItemIds(itemIds: Collection<Long>): List<ItemSnapshot> =
        itemIds
            .takeIf { it.isNotEmpty() }
            ?.let { itemSnapshotJpaRepository.findByItemIdInAndDeletedAtIsNullOrderByIdAsc(it) }
            .orEmpty()

    override fun reparentAll(
        fromItemId: Long,
        toItemId: Long,
    ): Int = itemSnapshotJpaRepository.reparentAll(fromItemId, toItemId)

    override fun findLatestByItemId(itemId: Long): ItemSnapshot? =
        itemSnapshotJpaRepository.findFirstByItemIdAndDeletedAtIsNullOrderByIdDesc(itemId)

    override fun findPriceHistoryByItemId(
        itemId: Long,
        limit: Int,
    ): List<ItemSnapshot> = itemSnapshotJpaRepository.findPriceHistoryByItemId(itemId, PageRequest.of(0, limit))

    override fun findById(id: Long): ItemSnapshot? = itemSnapshotJpaRepository.findByIdAndDeletedAtIsNull(id)

    override fun findByIds(ids: List<Long>): List<ItemSnapshot> =
        itemSnapshotJpaRepository.findByIdInAndDeletedAtIsNull(ids)
}
