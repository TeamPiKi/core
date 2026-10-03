package com.depromeet.piki.item.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ItemParseOutboxTest {
    @Test
    fun `새 아웃박스는 PENDING 이다`() {
        assertEquals(ItemParseOutboxStatus.PENDING, ItemParseOutbox(itemSnapshotId = 1L).status)
    }

    @Test
    fun `PENDING 을 집으면 PROCESSING 이 된다`() {
        val outbox = ItemParseOutbox(itemSnapshotId = 1L)

        outbox.claim()

        assertEquals(ItemParseOutboxStatus.PROCESSING, outbox.status)
    }

    @Test
    fun `PROCESSING 은 성공·실패로 닫히거나 PENDING 으로 되돌아간다`() {
        listOf(
            ItemParseOutbox::succeed to ItemParseOutboxStatus.SUCCEEDED,
            ItemParseOutbox::fail to ItemParseOutboxStatus.FAILED,
            ItemParseOutbox::requeue to ItemParseOutboxStatus.PENDING,
        ).forEach { (transit, expected) ->
            val outbox = processing()
            transit(outbox)
            assertEquals(expected, outbox.status)
        }
    }

    @Test
    fun `PENDING 이 아닌 아웃박스는 집을 수 없다`() {
        val outbox = processing()

        assertFailsWith<IllegalStateException> { outbox.claim() }
    }

    @Test
    fun `PROCESSING 이 아닌 아웃박스는 닫거나 되돌릴 수 없다`() {
        listOf(ItemParseOutbox::succeed, ItemParseOutbox::fail, ItemParseOutbox::requeue).forEach { transit ->
            assertFailsWith<IllegalStateException> { transit(ItemParseOutbox(itemSnapshotId = 1L)) }
        }
    }

    @Test
    fun `닫힌 아웃박스는 다시 닫을 수 없다`() {
        val outbox = processing().apply { succeed() }

        assertFailsWith<IllegalStateException> { outbox.fail() }
    }

    private fun processing(): ItemParseOutbox = ItemParseOutbox(itemSnapshotId = 1L).apply { claim() }
}
