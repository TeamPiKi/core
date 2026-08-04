package com.depromeet.piki.item.repository

import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.ItemStatus
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.util.UUID

@Repository
class ItemSnapshotRepositoryImpl(
    private val itemSnapshotJpaRepository: ItemSnapshotJpaRepository,
) : ItemSnapshotRepository {
    override fun save(snapshot: ItemSnapshot): ItemSnapshot = itemSnapshotJpaRepository.save(snapshot)

    override fun saveAll(snapshots: List<ItemSnapshot>): List<ItemSnapshot> =
        itemSnapshotJpaRepository.saveAll(snapshots)

    override fun findLatestInProgressByItemId(itemId: Long): ItemSnapshot? = itemSnapshotJpaRepository.findLatestInProgressByItemId(itemId)

    override fun findLatestMachineReadyByItemId(itemId: Long): ItemSnapshot? = itemSnapshotJpaRepository.findLatestMachineReadyByItemId(itemId)

    override fun findLatestMachineReadyByItemIds(itemIds: Collection<Long>): List<ItemSnapshot> =
        itemIds.takeIf { it.isNotEmpty() }?.let { itemSnapshotJpaRepository.findLatestMachineReadyByItemIds(it) }.orEmpty()

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

    override fun findByIdForUpdate(id: Long): ItemSnapshot? = itemSnapshotJpaRepository.findByIdForUpdate(id)

    override fun findByIds(ids: List<Long>): List<ItemSnapshot> =
        itemSnapshotJpaRepository.findByIdInAndDeletedAtIsNull(ids)

    override fun findDuePending(batchSize: Int): List<ItemSnapshot> =
        itemSnapshotJpaRepository.findByStatusForUpdate(ItemStatus.PENDING, PageRequest.of(0, batchSize))

    override fun findStaleProcessing(
        threshold: LocalDateTime,
        batchSize: Int,
    ): List<ItemSnapshot> =
        itemSnapshotJpaRepository.findStaleByStatusForUpdate(
            ItemStatus.PROCESSING,
            threshold,
            PageRequest.of(0, batchSize),
        )

    override fun findOverdue(
        threshold: LocalDateTime,
        batchSize: Int,
    ): List<ItemSnapshot> =
        itemSnapshotJpaRepository.findOverdueForUpdate(
            listOf(ItemStatus.PENDING, ItemStatus.PROCESSING),
            threshold,
            PageRequest.of(0, batchSize),
        )

    override fun acquireOwnership(
        snapshotId: Long,
        expectedAttempt: Int,
        now: LocalDateTime,
    ): Int = itemSnapshotJpaRepository.acquireOwnership(snapshotId, ItemStatus.PROCESSING, expectedAttempt, now)

    override fun renewOwnership(
        snapshotId: Long,
        attempt: Int,
        now: LocalDateTime,
    ): Int = itemSnapshotJpaRepository.renewOwnership(snapshotId, ItemStatus.PROCESSING, attempt, now)
}
