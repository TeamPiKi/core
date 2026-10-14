package com.depromeet.piki.support

import com.depromeet.piki.item.domain.ItemSlot
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

// 커밋하는 테스트용. 위시가 요청한 아웃박스와 확정된 아이템까지 지워야 다음 실행의 같은 URL 등록이 깨끗하게 시작한다
fun JdbcTemplate.deleteWishDataOf(userId: UUID) {
    val userBytes = uuidToBytes(userId)
    val wishIds = queryForList("SELECT id FROM wishes WHERE user_id = ?", Long::class.java, userBytes).filterNotNull()
    val itemIds =
        queryForList("SELECT item_id FROM wishes WHERE user_id = ? AND item_id IS NOT NULL", Long::class.java, userBytes)
            .filterNotNull()
            .distinct()
    deleteParseOutboxOf(wishIds.map(ItemSlot::wish))
    update("DELETE m FROM wish_memos m JOIN wishes w ON w.id = m.wish_id WHERE w.user_id = ?", userBytes)
    update("DELETE FROM wishes WHERE user_id = ?", userBytes)
    deleteItems(itemIds)
}

fun JdbcTemplate.wishItemIdOf(wishId: Long): Long? =
    queryForList("SELECT item_id FROM wishes WHERE id = ?", Long::class.java, wishId).firstOrNull()

fun JdbcTemplate.latestParseOutboxIdOf(slot: ItemSlot): Long =
    queryForObject(
        "SELECT id FROM item_parse_outbox WHERE slot_type = ? AND slot_id = ? ORDER BY id DESC LIMIT 1",
        Long::class.java,
        slot.type.name,
        slot.id,
    ) ?: error("요청자 $slot 의 아웃박스가 없다")
