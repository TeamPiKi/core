package com.depromeet.piki.item.repository

import com.depromeet.piki.item.domain.ItemSnapshot

interface ItemSnapshotRepository {
    fun save(snapshot: ItemSnapshot): ItemSnapshot

    fun findLatestByItemId(itemId: Long): ItemSnapshot?

    fun findLatestInProgressByItemId(itemId: Long): ItemSnapshot?

    fun findLatestMachineReadyByItemId(itemId: Long): ItemSnapshot?

    fun findAllByItemIds(itemIds: Collection<Long>): List<ItemSnapshot>

    fun reparentAll(
        fromItemId: Long,
        toItemId: Long,
    ): Int

    // 출처 미상 행은 서버 추출인지 수기인지 알 수 없어 가격 이력에서 뺀다.
    fun findPriceHistoryByItemId(
        itemId: Long,
        limit: Int,
    ): List<ItemSnapshot>

    fun findById(id: Long): ItemSnapshot?

    fun findByIds(ids: List<Long>): List<ItemSnapshot>
}
