package com.depromeet.piki.support

import com.depromeet.piki.item.domain.ItemParseOutbox
import com.depromeet.piki.item.domain.ItemSnapshot
import jakarta.persistence.EntityManager
import org.springframework.jdbc.core.JdbcTemplate

// 엔티티 전이를 거친다. JDBC 로 상태만 바꾸면 같은 트랜잭션에서 이미 관리 중인 엔티티는 옛 상태로 남는다.
fun EntityManager.claimParseOutbox(snapshot: ItemSnapshot): Long {
    val outbox = parseOutboxOf(snapshot) ?: ItemParseOutbox(itemSnapshotId = snapshot.getId()).also(::persist)
    outbox.claim()
    flush()
    return outbox.getId()
}

fun JdbcTemplate.deleteParseOutboxOf(itemIds: Collection<Long?>) {
    val ids = itemIds.filterNotNull()
    if (ids.isEmpty()) return
    val idList = ids.joinToString(",")
    val outboxOfItems = "SELECT o.id FROM item_parse_outbox o JOIN item_snapshots s ON s.id = o.item_snapshot_id WHERE s.item_id IN ($idList)"
    update("DELETE FROM item_parse_outbox WHERE id IN (SELECT id FROM ($outboxOfItems) t)")
}

private fun EntityManager.parseOutboxOf(snapshot: ItemSnapshot): ItemParseOutbox? =
    createQuery("select o from ItemParseOutbox o where o.itemSnapshotId = :itemSnapshotId", ItemParseOutbox::class.java)
        .setParameter("itemSnapshotId", snapshot.getId())
        .resultList
        .singleOrNull()
