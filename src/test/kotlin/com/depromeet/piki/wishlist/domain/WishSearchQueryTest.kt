package com.depromeet.piki.wishlist.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WishSearchQueryTest {
    @Test
    fun `안 보내면 검색하지 않는다`() {
        assertNull(WishSearchQuery.of(null))
    }

    @Test
    fun `비었거나 공백뿐이면 WISH-011 로 거절한다`() {
        listOf("", "   ", "　").forEach { raw ->
            val e = assertFailsWith<WishException> { WishSearchQuery.of(raw) }
            assertEquals(WishErrorCode.INVALID_SEARCH_QUERY, e.errorCode)
        }
    }

    @Test
    fun `앞뒤 공백을 지운 검색어로 부분 일치한다`() {
        val query = requireNotNull(WishSearchQuery.of("  에코 "))
        assertEquals("에코", query.value)
        assertTrue(query.matches("캔버스 에코백"))
    }

    @Test
    fun `대소문자를 가리지 않는다`() {
        assertTrue(requireNotNull(WishSearchQuery.of("eco")).matches("ECO Bag"))
    }

    @Test
    fun `상품명이 없는 카드는 찾지 않는다`() {
        assertFalse(requireNotNull(WishSearchQuery.of("에코")).matches(null))
    }

    @Test
    fun `일치하지 않으면 거짓이다`() {
        assertFalse(requireNotNull(WishSearchQuery.of("백팩")).matches("에코백"))
    }
}
