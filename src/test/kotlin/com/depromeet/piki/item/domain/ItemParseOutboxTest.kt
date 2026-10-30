package com.depromeet.piki.item.domain

import com.depromeet.piki.image.domain.ProductImageKey
import com.depromeet.piki.product.domain.ParseInputType
import com.depromeet.piki.product.domain.ProductLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ItemParseOutboxTest {
    @Test
    fun `새 아웃박스는 PENDING 이다`() {
        assertEquals(ItemParseOutboxStatus.PENDING, pending().status)
    }

    @Test
    fun `링크 입력은 원본 URL 과 추적 파라미터를 뺀 URL 을 든다`() {
        val link = ProductLink.parse("https://shop.example.com/p/1?utm_source=x")

        val outbox = ItemParseOutbox.of(ItemSlot.wish(7L), ParsePurpose.REGISTER, ParseInput.Link(link))

        assertEquals(ItemSlot.wish(7L), outbox.slot)
        assertEquals(ParseInputType.LINK, outbox.inputType)
        assertEquals("https://shop.example.com/p/1?utm_source=x", outbox.inputValue)
        assertEquals("https://shop.example.com/p/1", outbox.normalizedInput?.value)
        assertEquals(ParseInput.Link(link), outbox.input)
    }

    @Test
    fun `이미지 입력은 이미지 키가 값이자 중복 판정 키다`() {
        val outbox = ItemParseOutbox.of(ItemSlot.tournamentItem(3L), ParsePurpose.REGISTER, ParseInput.Image(ProductImageKey.parse(IMAGE_KEY)))

        assertEquals(ItemSlotType.TOURNAMENT_ITEM, outbox.slotType)
        assertEquals(ParseInputType.IMAGE, outbox.inputType)
        assertEquals(IMAGE_KEY, outbox.inputValue)
        assertEquals(IMAGE_KEY, outbox.normalizedInput?.value)
        assertEquals(ParseInput.Image(ProductImageKey.parse(IMAGE_KEY)), outbox.input)
    }

    @Test
    fun `요청 목적은 만들 때 정해지고 새로고침 여부는 그 값으로 답한다`() {
        val input = ParseInput.Link(ProductLink.parse("https://shop.example.com/p/1"))

        assertFalse(ItemParseOutbox.of(ItemSlot.wish(1L), ParsePurpose.REGISTER, input).isRefresh())
        assertTrue(ItemParseOutbox.of(ItemSlot.wish(1L), ParsePurpose.REFRESH, input).isRefresh())
    }

    @Test
    fun `저장 상한을 넘는 URL 은 중복 판정 키를 남기지 않는다`() {
        val link = ProductLink.parse("https://shop.example.com/p/1?q=" + "a".repeat(ProductLink.MAX_LENGTH))

        assertNull(ItemParseOutbox.of(ItemSlot.wish(1L), ParsePurpose.REGISTER, ParseInput.Link(link)).normalizedInput)
    }

    @Test
    fun `PENDING 을 집으면 PROCESSING 이 된다`() {
        val outbox = pending()

        outbox.claim()

        assertEquals(ItemParseOutboxStatus.PROCESSING, outbox.status)
    }

    @Test
    fun `PROCESSING 은 성공 실패로 닫히거나 PENDING 으로 되돌아간다`() {
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
        assertFailsWith<IllegalStateException> { processing().claim() }
    }

    @Test
    fun `멈춘 PROCESSING 을 회수하면 PENDING 으로 되돌아가고 회수 횟수가 는다`() {
        val outbox = processing()

        outbox.recover()

        assertEquals(ItemParseOutboxStatus.PENDING, outbox.status)
        assertEquals(1, outbox.recoveryCount)
    }

    @Test
    fun `한 번 회수한 아웃박스는 다시 회수할 수 없다`() {
        val outbox = processing().apply { recover() }
        outbox.claim()

        assertFalse(outbox.isRecoverable())
        assertFailsWith<IllegalStateException> { outbox.recover() }
        assertEquals(ItemParseOutboxStatus.PROCESSING, outbox.status)
    }

    @Test
    fun `PROCESSING 은 집을 때의 회수 횟수를 든 시도만 쥔다`() {
        val outbox = processing()

        assertTrue(outbox.isHeldBy(ParseAttempt(1L, recoveryCount = 0)))
        assertFalse(outbox.isHeldBy(ParseAttempt(1L, recoveryCount = 1)))
    }

    @Test
    fun `회수 뒤 다시 집히면 회수 전에 집은 시도는 행을 놓친다`() {
        val outbox = processing()
        val before = ParseAttempt(1L, outbox.recoveryCount)

        outbox.recover()
        outbox.claim()

        assertFalse(outbox.isHeldBy(before))
        assertTrue(outbox.isHeldBy(ParseAttempt(1L, outbox.recoveryCount)))
    }

    @Test
    fun `PROCESSING 이 아니면 어떤 시도도 행을 쥐지 않는다`() {
        assertFalse(pending().isHeldBy(ParseAttempt(1L, recoveryCount = 0)))
        assertFalse(processing().apply { succeed() }.isHeldBy(ParseAttempt(1L, recoveryCount = 0)))
    }

    @Test
    fun `PROCESSING 이 아닌 아웃박스는 닫거나 되돌릴 수 없다`() {
        listOf(ItemParseOutbox::succeed, ItemParseOutbox::fail, ItemParseOutbox::requeue, ItemParseOutbox::recover).forEach { transit ->
            assertFailsWith<IllegalStateException> { transit(pending()) }
        }
    }

    @Test
    fun `닫힌 아웃박스는 다시 닫을 수 없다`() {
        val outbox = processing().apply { succeed() }

        assertFailsWith<IllegalStateException> { outbox.fail() }
    }

    private fun pending(): ItemParseOutbox =
        ItemParseOutbox.of(ItemSlot.wish(1L), ParsePurpose.REGISTER, ParseInput.Link(ProductLink.parse("https://shop.example.com/p/1")))

    private fun processing(): ItemParseOutbox = pending().apply { claim() }

    companion object {
        private const val IMAGE_KEY = "items/raw/550e8400-e29b-41d4-a716-446655440000.png"
    }
}
