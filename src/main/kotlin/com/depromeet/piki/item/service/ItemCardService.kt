package com.depromeet.piki.item.service

import com.depromeet.piki.item.domain.CardQuery
import com.depromeet.piki.item.domain.ItemParseOutbox
import com.depromeet.piki.item.domain.ItemVersions
import com.depromeet.piki.item.domain.ItemSlot
import com.depromeet.piki.item.repository.ItemParseOutboxRepository
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import org.springframework.stereotype.Component

@Component
class ItemCardService(
    private val itemSnapshotRepository: ItemSnapshotRepository,
    private val itemParseOutboxRepository: ItemParseOutboxRepository,
) {
    fun cardOf(query: CardQuery): ItemCard = cardsOf(listOf(query)).getValue(query)

    fun cardsOf(queries: Collection<CardQuery>): Map<CardQuery, ItemCard> {
        if (queries.isEmpty()) return emptyMap()
        val latestRequests = latestRequestsOf(queries.map { it.slot })
        val versionsByItemId = versionsByItemId(queries.mapNotNull { it.itemId })
        return queries.associateWith { query -> cardFor(query, latestRequests, versionsByItemId) }
    }

    fun versionsOf(itemId: Long): ItemVersions = ItemVersions.of(itemSnapshotRepository.findAllByItemIds(listOf(itemId)))

    private fun cardFor(
        query: CardQuery,
        latestRequests: Map<ItemSlot, ItemParseOutbox>,
        versionsByItemId: Map<Long, ItemVersions>,
    ): ItemCard {
        val itemId = query.itemId ?: return unresolvedCardOf(query.slot, latestRequests)
        val versions = versionsByItemId[itemId] ?: error("item $itemId 의 버전이 없다")
        val refreshing = isRefreshing(query.slot, latestRequests)
        val startedAt = query.asOf ?: return ItemCard.current(versions, query.owner, refreshing)
        return ItemCard.fixedAt(startedAt, versions, query.owner)
    }

    // 아이템이 없는 카드는 요청과 함께 생기므로 요청이 없을 수 없다
    private fun unresolvedCardOf(
        slot: ItemSlot,
        latestRequests: Map<ItemSlot, ItemParseOutbox>,
    ): ItemCard {
        val request = latestRequests[slot] ?: error("$slot 는 아이템도 요청도 없다")
        return ItemCard.Unresolved(request)
    }

    // 위시를 토너먼트에 담은 카드와 아웃박스 이전에 만든 카드는 자기 요청이 없다. 요청이 없으면 새로고침 중일 수도 없다
    private fun isRefreshing(
        slot: ItemSlot,
        latestRequests: Map<ItemSlot, ItemParseOutbox>,
    ): Boolean = latestRequests[slot]?.isInProgress() ?: false

    private fun latestRequestsOf(slots: Collection<ItemSlot>): Map<ItemSlot, ItemParseOutbox> {
        val idsByType = slots.groupBy({ it.type }, { it.id })
        val latestRequests =
            idsByType.flatMap { (type, ids) ->
                itemParseOutboxRepository.findLatestBySlots(type, ids.distinct())
            }
        return latestRequests.associateBy { it.slot }
    }

    private fun versionsByItemId(itemIds: Collection<Long>): Map<Long, ItemVersions> {
        val snapshots = itemSnapshotRepository.findAllByItemIds(itemIds.distinct())
        return snapshots
            .groupBy { it.itemId }
            .mapValues { (_, versions) -> ItemVersions.of(versions) }
    }
}
