package com.depromeet.piki.folder.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FolderNameTest {
    @Test
    fun `앞뒤 공백을 지운 이름을 보유한다`() {
        assertEquals("겨울 아우터", FolderName.of("  겨울 아우터 ").value)
    }

    @Test
    fun `전각 공백도 앞뒤에서 지운다`() {
        assertEquals("선물", FolderName.of("　선물　").value)
    }

    @Test
    fun `null 이거나 공백뿐이면 FOLDER-002 로 거절한다`() {
        listOf(null, "", "   ", "　").forEach { raw ->
            val e = assertFailsWith<FolderException> { FolderName.of(raw) }
            assertEquals(FolderErrorCode.INVALID_NAME, e.errorCode)
        }
    }

    @Test
    fun `정확히 20자면 허용하고 21자면 거절한다`() {
        assertEquals(20, FolderName.of("가".repeat(20)).value.length)
        assertFailsWith<FolderException> { FolderName.of("가".repeat(21)) }
    }

    @Test
    fun `이모지는 한 글자로 센다`() {
        // 😀 는 UTF-16 으로 2칸이라 String.length 로 재면 40 이 된다
        assertEquals("😀".repeat(20), FolderName.of("😀".repeat(20)).value)
        assertFailsWith<FolderException> { FolderName.of("😀".repeat(21)) }
    }

    @Test
    fun `여러 코드 포인트로 된 가족 이모지도 한 글자로 센다`() {
        val family = "👨‍👩‍👧"
        assertEquals(family.repeat(10), FolderName.of(family.repeat(10)).value)
    }

    @Test
    fun `글자 수는 20자 이하여도 코드 포인트가 100개를 넘으면 거절한다`() {
        // 결합 악센트 100개가 붙은 a 는 화면에 1자지만 코드 포인트는 101개라 DB 칸(VARCHAR(100))을 넘는다
        val heavy = "a" + "́".repeat(100)
        assertFailsWith<FolderException> { FolderName.of(heavy) }
    }
}
