package com.depromeet.piki.support

import com.depromeet.piki.item.domain.ItemParseOutbox
import com.depromeet.piki.item.domain.ItemParseOutboxStatus
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

fun JdbcTemplate.parseOutboxStatusOf(itemId: Long): ItemParseOutboxStatus =
    queryForObject(
        "SELECT o.status FROM item_parse_outbox o JOIN item_snapshots s ON s.id = o.item_snapshot_id WHERE s.item_id = ?",
        String::class.java,
        itemId,
    )?.let(ItemParseOutboxStatus::valueOf) ?: error("item $itemId 의 아웃박스 행이 없다")

// 별칭(item_links)이 남으면 다음 실행의 같은 URL 등록이 지워진 item 을 가리켜 공유 매칭이 어긋남
fun JdbcTemplate.deleteItems(itemIds: Collection<Long>) {
    if (itemIds.isEmpty()) return
    val ids = itemIds.joinToString(",")
    update("DELETE FROM item_links WHERE item_id IN ($ids)")
    update("DELETE o FROM item_parse_outbox o JOIN item_snapshots s ON s.id = o.item_snapshot_id WHERE s.item_id IN ($ids)")
    update("DELETE FROM item_snapshots WHERE item_id IN ($ids)")
    update("DELETE FROM items WHERE id IN ($ids)")
}

private fun EntityManager.parseOutboxOf(snapshot: ItemSnapshot): ItemParseOutbox? =
    createQuery("select o from ItemParseOutbox o where o.itemSnapshotId = :itemSnapshotId", ItemParseOutbox::class.java)
        .setParameter("itemSnapshotId", snapshot.getId())
        .resultList
        .singleOrNull()
