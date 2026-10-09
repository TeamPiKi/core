package com.depromeet.piki.item.domain

import com.depromeet.piki.contracts.extraction.v1.ExtractionMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ItemSnapshotSourceTest {
    @Test
    fun `추출 경로가 출처로 번역된다`() {
        assertEquals(ItemSnapshotSource.SERVER, ItemSnapshotSource.of(ExtractionMethod.STRUCTURED))
        assertEquals(ItemSnapshotSource.SERVER_LLM, ItemSnapshotSource.of(ExtractionMethod.LLM))
    }

    @Test
    fun `모르는 경로와 없음은 출처를 비운다`() {
        assertNull(ItemSnapshotSource.of(null))
        assertNull(ItemSnapshotSource.of(ExtractionMethod.EXTRACTION_METHOD_UNSPECIFIED))
        assertNull(ItemSnapshotSource.of(ExtractionMethod.UNRECOGNIZED))
    }
}
