package com.depromeet.piki.wishlist.domain

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WishMemoTest {
    @Test
    fun `written 은 앞뒤 공백을 정리해 메모를 만든다`() {
        assertEquals("생일 선물 후보", WishMemo.written(1L, "  생일 선물 후보  ").content)
    }

    @Test
    fun `written 에 빈 문자열이나 공백만 주면 content 없는 지운 기록이 된다`() {
        assertNull(WishMemo.written(1L, "").content)
        assertNull(WishMemo.written(1L, "   ").content)
    }

    @Test
    fun `written 은 100자까지 허용하고 초과하면 실패한다`() {
        assertEquals(100, WishMemo.written(1L, "가".repeat(100)).content?.length)
        assertFailsWith<IllegalArgumentException> { WishMemo.written(1L, "가".repeat(101)) }
    }

    @Test
    fun `공백뿐인 content 로는 만들 수 없다`() {
        assertFailsWith<IllegalArgumentException> { WishMemo(wishId = 1L, content = " ", writtenAt = LocalDateTime.now()) }
    }

    @Test
    fun `sameContentAs 는 현재 메모와 내용이 같은지 본다`() {
        val current = WishMemo.written(1L, "선물용")

        assertTrue(WishMemo.written(1L, " 선물용 ").sameContentAs(current))
        assertFalse(WishMemo.written(1L, "사이즈 M").sameContentAs(current))
        assertFalse(WishMemo.written(1L, "").sameContentAs(current))
    }

    @Test
    fun `메모가 없을 때 지우면 현재와 같은 것으로 본다`() {
        assertTrue(WishMemo.written(1L, "").sameContentAs(null))
        assertTrue(WishMemo.written(1L, "").sameContentAs(WishMemo.written(1L, "")))
        assertFalse(WishMemo.written(1L, "선물용").sameContentAs(null))
    }
}
