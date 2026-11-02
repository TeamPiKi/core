package com.depromeet.piki.folder.repository

import com.depromeet.piki.folder.domain.FolderWish
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class FolderWishRepositoryImpl(
    private val folderWishJpaRepository: FolderWishJpaRepository,
) : FolderWishRepository {
    override fun saveAll(folderWishes: List<FolderWish>): List<FolderWish> = folderWishJpaRepository.saveAll(folderWishes)

    override fun findWishIdsByFolderId(folderId: Long): List<Long> = folderWishJpaRepository.findWishIdsByFolderId(folderId)

    override fun findLiveWishIdsByFolderIds(folderIds: Collection<Long>): Map<Long, List<Long>> {
        if (folderIds.isEmpty()) return emptyMap()
        return folderWishJpaRepository
            .findLiveEntriesByFolderIds(folderIds)
            .groupBy({ it.folderId }, { it.wishId })
    }

    override fun countLiveByFolderId(folderId: Long): Int = folderWishJpaRepository.countLiveByFolderId(folderId).toInt()

    override fun deleteByFolderIdAndWishIds(
        folderId: Long,
        wishIds: Collection<Long>,
    ): Int {
        if (wishIds.isEmpty()) return 0
        return folderWishJpaRepository.deleteByFolderIdAndWishIdIn(folderId, wishIds)
    }

    override fun deleteAllByFolderId(folderId: Long): Int = folderWishJpaRepository.deleteAllByFolderId(folderId)

    override fun hardDeleteAllByUserId(userId: UUID): Int = folderWishJpaRepository.hardDeleteAllByUserId(userId)
}
