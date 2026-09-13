package com.depromeet.piki.metrics.registration

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExternalEntryTest {
    @Test
    fun `아는 값은 그대로 해석된다`() {
        assertEquals(ExternalEntry.SHARE_SHEET, ExternalEntry.from("SHARE_SHEET"))
    }

    @Test
    fun `대소문자와 앞뒤 공백은 무시된다`() {
        assertEquals(ExternalEntry.SHARE_SHEET, ExternalEntry.from("share_sheet"))
        assertEquals(ExternalEntry.SHARE_SHEET, ExternalEntry.from("  Share_Sheet  "))
    }

    @Test
    fun `헤더가 없으면 기록 대상이 아니다`() {
        assertNull(ExternalEntry.from(null))
        assertNull(ExternalEntry.from(""))
        assertNull(ExternalEntry.from("   "))
    }

    @Test
    fun `서버가 모르는 값은 예외가 아니라 기록 대상에서 빠진다`() {
        assertNull(ExternalEntry.from("WIDGET"))
        assertNull(ExternalEntry.from("../../etc/passwd"))
    }
}
