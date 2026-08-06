package com.depromeet.piki.notification.handler

import kotlin.test.Test
import kotlin.test.assertEquals

class ItemParsingCompletedHandlerTest {
    @Test
    fun `10자 이하 이름은 그대로 쓴다`() {
        assertEquals("나이키 에어맥스", ItemParsingCompletedHandler.displayName("나이키 에어맥스"))
    }

    @Test
    fun `정확히 10자 이름은 자르지 않는다`() {
        val ten = "일이삼사오육칠팔구십" // 10자
        assertEquals(ten, ItemParsingCompletedHandler.displayName(ten))
    }

    @Test
    fun `10자 초과 이름은 앞 10자 + 말줄임표로 자른다`() {
        assertEquals("일이삼사오육칠팔구십…", ItemParsingCompletedHandler.displayName("일이삼사오육칠팔구십일이삼")) // 13자
    }

    @Test
    fun `이름이 null 이면 기본값을 쓴다`() {
        assertEquals("상품", ItemParsingCompletedHandler.displayName(null))
    }

    @Test
    fun `이름이 공백뿐이면 기본값을 쓴다`() {
        assertEquals("상품", ItemParsingCompletedHandler.displayName("   "))
    }
}
