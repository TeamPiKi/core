package com.depromeet.piki.wishlist.service

import java.util.UUID

// 위시 목록이 폴더에 묻는 것. 위시 모듈은 이 인터페이스만 알고 폴더 모듈이 구현한다(의존은 폴더 → 위시 한 방향).
// 폴더 id 를 받는 메서드는 내 폴더가 아니면 폴더 없음(404)으로 거절한다.
interface WishFolderPort {
    // 폴더에 든 살아 있는 위시를 최근 넣은 순으로. position 이 이 순서의 커서다
    fun entriesInFolder(
        userId: UUID,
        folderId: Long,
        afterPosition: Long?,
        limit: Int,
    ): List<FolderEntry>

    fun wishIdsInFolder(
        userId: UUID,
        folderId: Long,
    ): Set<Long>

    // 위시마다 들어 있는 폴더 id. 폴더가 없는 위시는 맵에 없다
    fun folderIdsOf(wishIds: Collection<Long>): Map<Long, List<Long>>
}

data class FolderEntry(
    val position: Long,
    val wishId: Long,
)
