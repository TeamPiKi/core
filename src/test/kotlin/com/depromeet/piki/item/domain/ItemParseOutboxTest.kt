package com.depromeet.piki.item.domain

import com.depromeet.piki.image.domain.ProductImageKey
import com.depromeet.piki.product.domain.ParseInputType
import com.depromeet.piki.product.domain.ProductLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ItemParseOutboxTest {
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

    companion object {
        private const val IMAGE_KEY = "items/raw/550e8400-e29b-41d4-a716-446655440000.png"
    }
}
