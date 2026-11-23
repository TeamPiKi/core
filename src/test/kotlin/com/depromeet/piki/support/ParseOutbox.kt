package com.depromeet.piki.support

import com.depromeet.piki.item.domain.ItemParseOutbox
import com.depromeet.piki.item.domain.ItemSlot
import com.depromeet.piki.item.domain.ParseRequestStatus
import com.depromeet.piki.item.service.ExtractionSettlement
import com.depromeet.piki.item.service.ItemParsingService
import com.depromeet.piki.product.service.ProductSnapshot
import jakarta.persistence.EntityManager
import org.springframework.jdbc.core.JdbcTemplate

fun ItemParseOutbox.failedBeforeSave(): ItemParseOutbox = apply { fail() }

// 등록 뒤 파싱이 끝난 것과 같은 경로로 아이템과 버전을 만든다
fun ItemParsingService.completeLatest(
    entityManager: EntityManager,
    slot: ItemSlot,
    extracted: ProductSnapshot,
): ExtractionSettlement = applyExtracted(entityManager.latestParseOutboxIdOf(slot), extracted)

fun ItemParsingService.failLatest(
    entityManager: EntityManager,
    slot: ItemSlot,
): Boolean = applyFailed(entityManager.latestParseOutboxIdOf(slot))

fun JdbcTemplate.parseRequestStatusOf(slot: ItemSlot): ParseRequestStatus =
    queryForObject(
        "SELECT status FROM item_parse_outbox WHERE slot_type = ? AND slot_id = ? ORDER BY id DESC LIMIT 1",
        String::class.java,
        slot.type.name,
        slot.id,
    )?.let(ParseRequestStatus::valueOf) ?: error("요청자 $slot 의 아웃박스가 없다")

fun JdbcTemplate.deleteItems(itemIds: Collection<Long>) {
    if (itemIds.isEmpty()) return
    val ids = itemIds.joinToString(",")
    update("DELETE FROM item_snapshots WHERE item_id IN ($ids)")
    update("DELETE FROM items WHERE id IN ($ids)")
}

fun JdbcTemplate.deleteParseOutboxOf(slots: Collection<ItemSlot>) {
    slots.forEach { update("DELETE FROM item_parse_outbox WHERE slot_type = ? AND slot_id = ?", it.type.name, it.id) }
}

private fun EntityManager.latestParseOutboxIdOf(slot: ItemSlot): Long {
    flush()
    return latestParseOutboxOf(slot)?.getId() ?: error("요청자 $slot 의 아웃박스가 없다")
}

private fun EntityManager.latestParseOutboxOf(slot: ItemSlot): ItemParseOutbox? =
    createQuery(
        "select o from ItemParseOutbox o where o.slotType = :type and o.slotId = :id order by o.id desc",
        ItemParseOutbox::class.java,
    ).setParameter("type", slot.type)
        .setParameter("id", slot.id)
        .setMaxResults(1)
        .resultList
        .firstOrNull()
