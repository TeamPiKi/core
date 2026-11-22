package com.depromeet.piki.wishlist.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WishCursorTest {
    @ParameterizedTest
    @ValueSource(strings = ["", " ", "   "])
    fun `null·빈·공백 cursor 는 첫 페이지를 의미하는 null 로 파싱된다`(raw: String) {
        assertNull(WishCursor.parse(raw))
    }

    @Test
    fun `cursor 가 null 이면 null 로 파싱된다`() {
        assertNull(WishCursor.parse(null))
    }

    @ParameterizedTest
    @ValueSource(strings = ["1024", " 1024 ", "-5", "0"])
    fun `숫자로 변환 가능한 cursor 는 그 값으로 파싱된다`(raw: String) {
        assertEquals(raw.trim().toLong(), WishCursor.parse(raw)?.position)
    }

    @ParameterizedTest
    @ValueSource(strings = ["abc", "12a", "1.5", "9999999999999999999999"])
    fun `숫자로 변환 불가한 cursor 는 WishException 으로 거부된다`(raw: String) {
        assertFailsWith<WishException> { WishCursor.parse(raw) }
    }
}
