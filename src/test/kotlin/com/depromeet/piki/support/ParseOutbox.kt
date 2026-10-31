package com.depromeet.piki.support

import com.depromeet.piki.common.outbox.OutboxAttempt
import com.depromeet.piki.common.outbox.OutboxStatus
import com.depromeet.piki.item.domain.ItemParseOutbox
import com.depromeet.piki.item.domain.ItemSlot
import com.depromeet.piki.item.service.ExtractionSettlement
import com.depromeet.piki.item.service.ItemParsingService
import com.depromeet.piki.product.service.ProductSnapshot
import jakarta.persistence.EntityManager
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.LocalDateTime

// 엔티티 전이를 거친다. JDBC 로 상태만 바꾸면 같은 트랜잭션에서 이미 관리 중인 엔티티는 옛 상태로 남는다.
fun EntityManager.claimLatestParseOutbox(slot: ItemSlot): OutboxAttempt {
    val outbox = latestParseOutboxOf(slot) ?: error("요청자 $slot 의 아웃박스가 없다")
    outbox.claim()
    flush()
    return outbox.attempt()
}

// 저장 전이라 id 가 없어 attempt() 대신 회수 횟수만 맞춘 시도로 닫는다
fun ItemParseOutbox.failedBeforeSave(): ItemParseOutbox =
    apply {
        claim()
        fail(OutboxAttempt(outboxId = 0L, recoveryCount = recoveryCount))
    }

// 등록 뒤 파싱이 끝난 것과 같은 경로로 아이템과 버전을 만든다
fun ItemParsingService.completeLatest(
    entityManager: EntityManager,
    slot: ItemSlot,
    extracted: ProductSnapshot,
): ExtractionSettlement = markExtracted(entityManager.claimLatestParseOutbox(slot), extracted)

fun ItemParsingService.failLatest(
    entityManager: EntityManager,
    slot: ItemSlot,
): Boolean = markFailed(entityManager.claimLatestParseOutbox(slot))

fun JdbcTemplate.parseOutboxStatusOf(slot: ItemSlot): OutboxStatus =
    queryForObject(
        "SELECT status FROM item_parse_outbox WHERE slot_type = ? AND slot_id = ? ORDER BY id DESC LIMIT 1",
        String::class.java,
        slot.type.name,
        slot.id,
    )?.let(OutboxStatus::valueOf) ?: error("요청자 $slot 의 아웃박스가 없다")

fun JdbcTemplate.parseOutboxRecoveryCountOf(slot: ItemSlot): Int =
    queryForObject("SELECT recovery_count FROM item_parse_outbox WHERE id = ?", Int::class.java, latestParseOutboxIdOf(slot))
        ?: error("요청자 $slot 의 아웃박스가 없다")

// 테스트 컨텍스트는 스케줄링이 꺼져 있어 워커가 집은 뒤 멈춘 상태를 직접 만든다
fun JdbcTemplate.markParseOutboxStuck(
    slot: ItemSlot,
    claimedAgo: Duration,
    recoveryCount: Int = 0,
) {
    update(
        "UPDATE item_parse_outbox SET status = 'PROCESSING', recovery_count = ?, updated_at = ? WHERE id = ?",
        recoveryCount,
        Timestamp.valueOf(LocalDateTime.now().minus(claimedAgo)),
        latestParseOutboxIdOf(slot),
    )
}

fun JdbcTemplate.deleteItems(itemIds: Collection<Long>) {
    if (itemIds.isEmpty()) return
    val ids = itemIds.joinToString(",")
    update("DELETE FROM item_snapshots WHERE item_id IN ($ids)")
    update("DELETE FROM items WHERE id IN ($ids)")
}

fun JdbcTemplate.deleteParseOutboxOf(slots: Collection<ItemSlot>) {
    slots.forEach { update("DELETE FROM item_parse_outbox WHERE slot_type = ? AND slot_id = ?", it.type.name, it.id) }
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
