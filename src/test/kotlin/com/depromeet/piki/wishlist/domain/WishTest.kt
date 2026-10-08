package com.depromeet.piki.wishlist.domain

import com.depromeet.piki.item.domain.ItemSlot
import com.depromeet.piki.item.domain.CardQuery
import com.depromeet.piki.support.withId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WishTest {
    @Test
    fun `파싱 성공 전 위시는 아이템 없이 생성된다`() {
        assertNull(Wish(userId = UUID.randomUUID(), itemId = null).itemId)
    }

    @Test
    fun `itemId 가 0 이하면 생성에 실패한다`() {
        assertFailsWith<IllegalArgumentException> { Wish(userId = UUID.randomUUID(), itemId = 0L) }
        assertFailsWith<IllegalArgumentException> { Wish(userId = UUID.randomUUID(), itemId = -1L) }
    }

    @Test
    fun `fill 은 비어 있던 아이템을 확정한다`() {
        val wish = Wish(userId = UUID.randomUUID(), itemId = null)

        wish.fill(3L)

        assertEquals(3L, wish.itemId)
    }

    @Test
    fun `같은 아이템으로 다시 확정하면 멱등이다`() {
        val wish = Wish(userId = UUID.randomUUID(), itemId = 3L)

        wish.fill(3L)

        assertEquals(3L, wish.itemId)
    }

    @Test
    fun `이미 확정된 위시를 다른 아이템으로 확정하면 코드 버그다`() {
        val wish = Wish(userId = UUID.randomUUID(), itemId = 3L)

        assertFailsWith<IllegalStateException> { wish.fill(4L) }
    }

    @Test
    fun `fill 에 0 이하 id 를 주면 실패한다`() {
        val wish = Wish(userId = UUID.randomUUID(), itemId = null)

        assertFailsWith<IllegalArgumentException> { wish.fill(0L) }
    }

    @Test
    fun `cardQuery 는 이 위시를 요청자로 주인과 아이템을 담는다`() {
        val owner = UUID.randomUUID()
        val wish = Wish(userId = owner, itemId = 3L).withId(9L)

        assertEquals(CardQuery(slot = ItemSlot.wish(9L), itemId = 3L, owner = owner), wish.cardQuery())
    }

    @Test
    fun `verifyOwnedBy 는 소유자가 아니면 WishException 을 던진다`() {
        val wish = Wish(userId = UUID.randomUUID(), itemId = 1L)

        assertFailsWith<WishException> { wish.verifyOwnedBy(UUID.randomUUID()) }
    }

    @Test
    fun `verifyOwnedBy 는 소유자가 호출하면 예외 없이 통과한다`() {
        val owner = UUID.randomUUID()

        Wish(userId = owner, itemId = 1L).verifyOwnedBy(owner)
    }

    @Test
    fun `updateMemo 는 앞뒤 공백을 정리해 메모를 저장한다`() {
        val wish = Wish(userId = UUID.randomUUID(), itemId = 1L)

        wish.updateMemo("  생일 선물 후보  ")

        assertEquals("생일 선물 후보", wish.memo)
    }

    @Test
    fun `updateMemo 에 빈 문자열이나 공백만 주면 메모가 삭제된다`() {
        val wish = Wish(userId = UUID.randomUUID(), itemId = 1L)

        wish.updateMemo("메모")
        wish.updateMemo("   ")

        assertNull(wish.memo)
    }

    @Test
    fun `updateMemo 는 100자까지 허용하고 초과하면 실패한다`() {
        val wish = Wish(userId = UUID.randomUUID(), itemId = 1L)

        wish.updateMemo("가".repeat(100))

        assertEquals(100, wish.memo?.length)
        assertFailsWith<IllegalArgumentException> { wish.updateMemo("가".repeat(101)) }
    }
}
