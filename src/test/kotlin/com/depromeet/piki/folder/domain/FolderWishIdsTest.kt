package com.depromeet.piki.folder.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FolderWishIdsTest {
    @Test
    fun `중복 id 는 하나로 정규화한다`() {
        assertEquals(listOf(1L, 2L), FolderWishIds.of(listOf(1L, 1L, 2L)).values)
    }

    @Test
    fun `null 이거나 비면 FOLDER-003 으로 거절한다`() {
        listOf(null, emptyList<Long>()).forEach { raw ->
            val e = assertFailsWith<FolderException> { FolderWishIds.of(raw) }
            assertEquals(FolderErrorCode.INVALID_WISH_COUNT, e.errorCode)
        }
    }

    @Test
    fun `정확히 100개면 허용하고 101개면 거절한다`() {
        assertEquals(100, FolderWishIds.of((1L..100L).toList()).values.size)
        assertFailsWith<FolderException> { FolderWishIds.of((1L..101L).toList()) }
    }

    @Test
    fun `중복을 빼면 100개가 되는 입력은 허용한다`() {
        assertEquals(100, FolderWishIds.of((1L..100L).toList() + 100L).values.size)
    }
}
