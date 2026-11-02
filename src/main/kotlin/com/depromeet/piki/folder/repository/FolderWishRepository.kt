package com.depromeet.piki.folder.repository

import com.depromeet.piki.folder.domain.FolderWish
import java.util.UUID

interface FolderWishRepository {
    fun saveAll(folderWishes: List<FolderWish>): List<FolderWish>

    fun findWishIdsByFolderId(folderId: Long): List<Long>

    // 폴더 id 별로, 최근 넣은 순. 지워진 위시는 빠진다
    fun findLiveWishIdsByFolderIds(folderIds: Collection<Long>): Map<Long, List<Long>>

    fun countLiveByFolderId(folderId: Long): Int

    // 폴더 안 살아 있는 위시 한 페이지. 최근 넣은 순
    fun findLivePositions(
        folderId: Long,
        afterPosition: Long?,
        limit: Int,
    ): List<FolderWishPositionView>

    fun findFolderIdsByWishIds(wishIds: Collection<Long>): Map<Long, List<Long>>

    fun findWishIdsByFolderIdAmong(
        folderId: Long,
        wishIds: Collection<Long>,
    ): Set<Long>

    fun deleteByFolderIdAndWishIds(
        folderId: Long,
        wishIds: Collection<Long>,
    ): Int

    fun findFolderIdsByWishId(wishId: Long): List<Long>

    fun deleteAllByWishId(wishId: Long): Int

    fun deleteAllByFolderId(folderId: Long): Int

    fun hardDeleteAllByUserId(userId: UUID): Int
}
