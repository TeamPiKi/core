package com.depromeet.piki.item.domain

import com.depromeet.piki.product.domain.ProductLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ItemParseOutboxTest {
    @Test
    fun `새 아웃박스는 PENDING 이다`() {
        assertEquals(ItemParseOutboxStatus.PENDING, pending().status)
    }

    @Test
    fun `링크 입력은 원본 URL 과 추적 파라미터를 뺀 URL 을 들고 이미지 키는 비운다`() {
        val link = ProductLink.parse("https://shop.example.com/p/1?utm_source=x")

        val outbox = ItemParseOutbox.of(ParseRequester.wish(7L), ParseInput.Link(link))

        assertEquals(ParseRequester.wish(7L), outbox.requester)
        assertEquals(link, outbox.sourceUrl)
        assertEquals("https://shop.example.com/p/1", outbox.sourceUrlWithoutTracking)
        assertNull(outbox.sourceImageKey)
        assertEquals(ParseInput.Link(link), outbox.input)
    }

    @Test
    fun `이미지 입력은 이미지 키만 든다`() {
        val outbox = ItemParseOutbox.of(ParseRequester.tournamentItem(3L), ParseInput.Image("images/a.png"))

        assertEquals(ParseRequesterType.TOURNAMENT_ITEM, outbox.requesterType)
        assertNull(outbox.sourceUrl)
        assertNull(outbox.sourceUrlWithoutTracking)
        assertEquals(ParseInput.Image("images/a.png"), outbox.input)
    }

    @Test
    fun `URL 과 이미지 키를 둘 다 들거나 둘 다 비우면 생성에 실패한다`() {
        val link = ProductLink.parse("https://shop.example.com/p/1")
        assertFailsWith<IllegalArgumentException> {
            ItemParseOutbox(ParseRequesterType.WISH, 1L, link, null, "images/a.png")
        }
        assertFailsWith<IllegalArgumentException> {
            ItemParseOutbox(ParseRequesterType.WISH, 1L, null, null, null)
        }
    }

    @Test
    fun `저장 상한을 넘는 URL 은 추적 파라미터를 뺀 URL 을 남기지 않는다`() {
        val link = ProductLink.parse("https://shop.example.com/p/1?q=" + "a".repeat(ProductLink.MAX_LENGTH))

        assertNull(ItemParseOutbox.sourceUrlWithoutTrackingOf(link))
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
    fun `PROCESSING 이 아닌 아웃박스는 닫거나 되돌릴 수 없다`() {
        listOf(ItemParseOutbox::succeed, ItemParseOutbox::fail, ItemParseOutbox::requeue).forEach { transit ->
            assertFailsWith<IllegalStateException> { transit(pending()) }
        }
    }

    @Test
    fun `닫힌 아웃박스는 다시 닫을 수 없다`() {
        val outbox = processing().apply { succeed() }

        assertFailsWith<IllegalStateException> { outbox.fail() }
    }

    private fun pending(): ItemParseOutbox =
        ItemParseOutbox.of(ParseRequester.wish(1L), ParseInput.Link(ProductLink.parse("https://shop.example.com/p/1")))

    private fun processing(): ItemParseOutbox = pending().apply { claim() }
}
