package com.depromeet.piki.item.domain

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ItemSnapshotSourceTest {
    @ParameterizedTest
    @CsvSource(
        "STRUCTURED, SERVER",
        "LLM, SERVER_LLM",
    )
    fun `추출 경로 wire 문자열이 출처로 번역된다`(
        wire: String,
        expected: ItemSnapshotSource,
    ) {
        assertEquals(expected, ItemSnapshotSource.fromWireMethod(wire))
    }

    @Test
    fun `모르는 값과 null 은 출처 미기록으로 둔다 - tolerant reader`() {
        assertNull(ItemSnapshotSource.fromWireMethod(null))
        assertNull(ItemSnapshotSource.fromWireMethod("SOME_NEW_METHOD"))
        assertNull(ItemSnapshotSource.fromWireMethod("structured"))
    }
}
