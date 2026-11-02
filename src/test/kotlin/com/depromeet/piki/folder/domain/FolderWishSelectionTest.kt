package com.depromeet.piki.folder.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class FolderWishSelectionTest {
    @Test
    fun `all 이 true 이고 wishIds 가 없으면 전체 선택이다`() {
        assertEquals(FolderWishSelection.All, FolderWishSelection.of(all = true, wishIds = null))
        assertEquals(FolderWishSelection.All, FolderWishSelection.of(all = true, wishIds = emptyList()))
    }

    @Test
    fun `all 과 wishIds 를 함께 보내면 FOLDER-005 로 거절한다`() {
        val e = assertFailsWith<FolderException> { FolderWishSelection.of(all = true, wishIds = listOf(1L)) }
        assertEquals(FolderErrorCode.INVALID_SELECTION, e.errorCode)
    }

    @Test
    fun `all 이 없거나 false 면 wishIds 로 고른다`() {
        val selection = assertIs<FolderWishSelection.Ids>(FolderWishSelection.of(all = null, wishIds = listOf(1L, 1L, 2L)))
        assertEquals(listOf(1L, 2L), selection.ids.values)
        assertIs<FolderWishSelection.Ids>(FolderWishSelection.of(all = false, wishIds = listOf(1L)))
    }

    @Test
    fun `all 없이 wishIds 도 없으면 기존처럼 FOLDER-003 으로 거절한다`() {
        val e = assertFailsWith<FolderException> { FolderWishSelection.of(all = null, wishIds = null) }
        assertEquals(FolderErrorCode.INVALID_WISH_COUNT, e.errorCode)
    }
}
