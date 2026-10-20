package com.depromeet.piki.item.service

import com.depromeet.piki.contracts.extraction.v1.ExtractionMethod
import com.depromeet.piki.image.domain.ProductImageKey
import com.depromeet.piki.item.domain.ItemErrorCode
import com.depromeet.piki.item.domain.ItemException
import com.depromeet.piki.item.domain.ItemParseOutbox
import com.depromeet.piki.item.domain.ItemSlot
import com.depromeet.piki.item.domain.ItemSnapshot
import com.depromeet.piki.item.domain.ItemStatus
import com.depromeet.piki.item.domain.ParseInput
import com.depromeet.piki.item.domain.ParsePurpose
import com.depromeet.piki.item.domain.UserInput
import com.depromeet.piki.product.domain.ProductLink
import com.depromeet.piki.support.imageItem
import com.depromeet.piki.support.linkItem
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ItemCardTest {
    @Test
    fun `파싱 중인 요청만 있는 카드는 PARSING 이고 값을 다 보내도 사용자 입력을 거부한다`() {
        val card = ItemCard.Unresolved(request())

        assertEquals(ItemStatus.PARSING, card.status)
        val e = assertFailsWith<ItemException> { card.userInputValues(fullInput()) }
        assertEquals(ItemErrorCode.PARSING_IN_PROGRESS, e.errorCode)
    }

    @Test
    fun `바꿀 값이 하나도 없는 입력은 카드 상태와 무관하게 거부한다`() {
        val empties =
            listOf(
                UserInput(name = null, price = null, imageUrl = null, currency = null),
                UserInput(name = " ", price = null, imageUrl = null, currency = ""),
            )
        val cards = listOf(ItemCard.Resolved(item = linkItem(ITEM_URL), display = version(), refreshing = false), ItemCard.Unresolved(failedRequest()))

        for (card in cards) {
            for (empty in empties) {
                val e = assertFailsWith<ItemException> { card.userInputValues(empty) }
                assertEquals(ItemErrorCode.EMPTY_USER_INPUT, e.errorCode, "${card.status} 카드, $empty")
            }
        }
    }

    @Test
    fun `실패한 요청만 있는 카드는 보낸 값을 그대로 쓴다`() {
        val card = ItemCard.Unresolved(failedRequest())

        assertEquals(ItemStatus.FAILED, card.status)
        val values = card.userInputValues(fullInput())
        assertEquals("직접 입력", values.name)
        assertEquals(5_000, values.price)
        assertEquals("https://img.example.com/m.png", values.imageUrl)
    }

    @Test
    fun `실패한 요청만 있는 카드는 보낸 값에 이미지가 없으면 거부한다`() {
        val card = ItemCard.Unresolved(failedRequest())

        val e = assertFailsWith<ItemException> { card.userInputValues(UserInput(name = "직접 입력", price = 5_000, imageUrl = null, currency = null)) }
        assertEquals(ItemErrorCode.IMAGE_REQUIRED_FOR_READY, e.errorCode)
    }

    @Test
    fun `값이 다 있는 카드는 보내지 않은 필드를 지금 보이는 값으로 채운다`() {
        val card = ItemCard.Resolved(item = linkItem(ITEM_URL), display = version(), refreshing = false)

        val values = card.userInputValues(UserInput(name = null, price = 79_000, imageUrl = null, currency = null))

        assertEquals("나이키 에어포스", values.name)
        assertEquals(79_000, values.price)
        assertEquals("https://img.example.com/a.png", values.imageUrl)
        assertEquals("KRW", values.currency)
    }

    @Test
    fun `미완성 카드는 비어 있는 필드를 보내지 않으면 거부한다`() {
        val card = ItemCard.Resolved(item = linkItem(ITEM_URL), display = version(price = null), refreshing = false)

        assertEquals(ItemStatus.INCOMPLETE, card.status)
        val e = assertFailsWith<ItemException> { card.userInputValues(UserInput(name = "이름만", price = null, imageUrl = null, currency = null)) }
        assertEquals(ItemErrorCode.PRICE_REQUIRED_FOR_READY, e.errorCode)
    }

    @Test
    fun `미완성 카드는 비어 있는 필드만 보내도 나머지를 지금 값으로 채운다`() {
        val card = ItemCard.Resolved(item = linkItem(ITEM_URL), display = version(price = null), refreshing = false)

        assertEquals(10_000, card.userInputValues(UserInput(name = null, price = 10_000, imageUrl = null, currency = null)).price)
    }

    @Test
    fun `새로고침 중인 카드는 값이 있어도 사용자 입력을 거부한다`() {
        val card = ItemCard.Resolved(item = linkItem(ITEM_URL), display = version(), refreshing = true)

        assertEquals(ItemStatus.READY, card.status)
        val e = assertFailsWith<ItemException> { card.userInputValues(UserInput(name = "바꾼 이름", price = null, imageUrl = null, currency = null)) }
        assertEquals(ItemErrorCode.PARSING_IN_PROGRESS, e.errorCode)
    }

    @Test
    fun `출처 링크는 상품이 정해지기 전엔 요청한 링크이고 정해진 뒤엔 상품 주소다`() {
        val requested = ProductLink.parse("https://short.example.net/abc")

        assertEquals(requested, ItemCard.Unresolved(request(ParseInput.Link(requested))).sourceLink)
        assertEquals(ProductLink.parse(ITEM_URL), ItemCard.Resolved(item = linkItem(ITEM_URL), display = version(), refreshing = false).sourceLink)
    }

    @Test
    fun `이미지로 등록한 카드는 상품이 정해지기 전후 모두 출처 링크가 없다`() {
        assertNull(ItemCard.Unresolved(request(ParseInput.Image(ProductImageKey.newRaw("png")))).sourceLink)
        assertNull(ItemCard.Resolved(item = imageItem(), display = version(), refreshing = false).sourceLink)
    }

    private fun request(input: ParseInput = ParseInput.Link(ProductLink.parse("https://shop.example.com/products/1"))): ItemParseOutbox =
        ItemParseOutbox.of(ItemSlot.wish(1L), ParsePurpose.REGISTER, input)

    private fun failedRequest(): ItemParseOutbox =
        request().also {
            it.claim()
            it.fail()
        }

    private fun fullInput(): UserInput = UserInput(name = "직접 입력", price = 5_000, imageUrl = "https://img.example.com/m.png", currency = "KRW")

    private fun version(price: Int? = 99_000): ItemSnapshot =
        ItemSnapshot(
            itemId = 1L,
            name = "나이키 에어포스",
            imageUrl = "https://img.example.com/a.png",
            price = price,
            currency = "KRW",
            method = ExtractionMethod.STRUCTURED,
            userInput = false,
            createdBy = UUID.randomUUID(),
        )

    companion object {
        private const val ITEM_URL = "https://shop.example.com/products/1"
    }
}
