package com.depromeet.piki.item.service

import com.depromeet.piki.item.domain.CardQuery
import com.depromeet.piki.item.domain.Item
import com.depromeet.piki.item.domain.ItemParseOutbox
import com.depromeet.piki.item.domain.ItemSlot
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.ItemVersions
import com.depromeet.piki.item.repository.ItemParseOutboxRepository
import com.depromeet.piki.item.repository.ItemRepository
import com.depromeet.piki.item.repository.ItemSnapshotRepository
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class ItemCardService(
    private val itemRepository: ItemRepository,
    private val itemSnapshotRepository: ItemSnapshotRepository,
    private val itemParseOutboxRepository: ItemParseOutboxRepository,
) {
    fun cardOf(query: CardQuery): ItemCard = cardsOf(listOf(query)).getValue(query)

    fun cardsOf(queries: Collection<CardQuery>): Map<CardQuery, ItemCard> =
        cardsOf(queries, itemRepository::findByIds, itemSnapshotRepository::findAllByItemIds)

    // 일반 읽기는 트랜잭션 첫 읽기 때 고정된 스냅샷이라 아이템 잠금을 기다리는 동안 커밋된 버전을 못 본다
    fun lockedCardsOf(queries: Collection<CardQuery>): Map<CardQuery, ItemCard> =
        cardsOf(queries, itemRepository::findByIdsForShare, itemSnapshotRepository::findAllByItemIdsForShare)

    private fun cardsOf(
        queries: Collection<CardQuery>,
        itemsOf: (List<Long>) -> List<Item>,
        currentVersionsOf: (Collection<Long>) -> List<ItemSnapshot>,
    ): Map<CardQuery, ItemCard> {
        if (queries.isEmpty()) return emptyMap()
        val latestRequests = latestRequestsOf(queries.map { it.slot })
        val itemsById = itemsOf(queries.mapNotNull { it.itemId }.distinct()).associateBy { it.getId() }
        val versionsByScope = versionsByScope(queries, currentVersionsOf)
        return queries.associateWith { query -> cardFor(query, latestRequests, itemsById, versionsByScope) }
    }

    fun versionsOf(itemId: Long): ItemVersions = ItemVersions.of(itemSnapshotRepository.findAllByItemIds(listOf(itemId)))

    private fun cardFor(
        query: CardQuery,
        latestRequests: Map<ItemSlot, ItemParseOutbox>,
        itemsById: Map<Long, Item>,
        versionsByScope: Map<VersionScope, ItemVersions>,
    ): ItemCard {
        val itemId = query.itemId ?: return unresolvedCardOf(query.slot, latestRequests)
        val item = itemsById[itemId] ?: error("item $itemId 가 없다")
        val versions = versionsByScope[VersionScope(itemId, query.asOf)] ?: error("item $itemId 의 버전이 없다")
        val display = versions.displayFor(query.owner)
        // 시작한 토너먼트는 시작 뒤의 새로고침이 보이지 않는다
        query.asOf ?: return ItemCard.Resolved(item, display, isRefreshing(query.slot, latestRequests))
        return ItemCard.Resolved(item, display, refreshing = false)
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

    // 시작한 토너먼트는 시작 시각까지의 버전만 읽는다. 그 뒤 버전은 메모리에도 오지 않는다
    private fun versionsByScope(
        queries: Collection<CardQuery>,
        currentVersionsOf: (Collection<Long>) -> List<ItemSnapshot>,
    ): Map<VersionScope, ItemVersions> =
        queries
            .mapNotNull { query -> query.itemId?.let { VersionScope(it, query.asOf) } }
            .distinct()
            .groupBy({ it.asOf }, { it.itemId })
            .flatMap { (asOf, itemIds) ->
                snapshotsOf(itemIds, asOf, currentVersionsOf)
                    .groupBy { it.itemId }
                    .map { (itemId, versions) -> VersionScope(itemId, asOf) to ItemVersions.of(versions) }
            }.toMap()

    private fun snapshotsOf(
        itemIds: List<Long>,
        asOf: LocalDateTime?,
        currentVersionsOf: (Collection<Long>) -> List<ItemSnapshot>,
    ): List<ItemSnapshot> {
        asOf ?: return currentVersionsOf(itemIds)
        return itemSnapshotRepository.findAllByItemIdsAsOf(itemIds, asOf)
    }

    private data class VersionScope(
        val itemId: Long,
        val asOf: LocalDateTime?,
    )
}
