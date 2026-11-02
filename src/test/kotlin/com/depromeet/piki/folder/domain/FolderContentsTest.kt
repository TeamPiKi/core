package com.depromeet.piki.folder.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FolderContentsTest {
    @Test
    fun `넣기는 아직 없는 위시만 돌려준다`() {
        val contents = FolderContents(folderId = 1, wishIds = listOf(10L, 11L))
        assertEquals(listOf(12L), contents.put(listOf(10L, 12L)))
    }

    @Test
    fun `넣기에 같은 위시가 두 번 오면 한 번만 돌려준다`() {
        val contents = FolderContents(folderId = 1, wishIds = emptyList())
        assertEquals(listOf(12L), contents.put(listOf(12L, 12L)))
    }

    @Test
    fun `모두 이미 있으면 넣을 것이 없다`() {
        val contents = FolderContents(folderId = 1, wishIds = listOf(10L))
        assertEquals(emptyList(), contents.put(listOf(10L)))
    }

    @Test
    fun `빼기는 들어 있는 위시만 돌려준다`() {
        val contents = FolderContents(folderId = 1, wishIds = listOf(10L, 11L))
        assertEquals(listOf(11L), contents.takeOut(listOf(11L, 99L)))
    }

    @Test
    fun `이동은 이 폴더에 든 위시를 빼고 대상에 없는 것만 넣는다`() {
        val source = FolderContents(folderId = 1, wishIds = listOf(10L, 11L, 12L))
        val target = FolderContents(folderId = 2, wishIds = listOf(11L))

        val move = source.moveTo(target, listOf(10L, 11L))

        assertEquals(listOf(10L, 11L), move.takeOut)
        assertEquals(listOf(10L), move.putIntoTarget)
    }

    @Test
    fun `이 폴더에 없는 위시는 옮기지 않는다`() {
        val source = FolderContents(folderId = 1, wishIds = listOf(10L))
        val target = FolderContents(folderId = 2, wishIds = emptyList())

        val move = source.moveTo(target, listOf(10L, 99L))

        assertEquals(listOf(10L), move.takeOut)
        assertEquals(listOf(10L), move.putIntoTarget)
    }

    @Test
    fun `같은 폴더로 옮기면 FOLDER-004 로 거절한다`() {
        val source = FolderContents(folderId = 1, wishIds = listOf(10L))
        val e = assertFailsWith<FolderException> { source.moveTo(FolderContents(folderId = 1, wishIds = listOf(10L)), listOf(10L)) }
        assertEquals(FolderErrorCode.SAME_FOLDER_MOVE, e.errorCode)
    }
}
