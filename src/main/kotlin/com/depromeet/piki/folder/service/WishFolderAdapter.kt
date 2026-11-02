package com.depromeet.piki.folder.service

import com.depromeet.piki.folder.domain.FolderException
import com.depromeet.piki.folder.repository.FolderRepository
import com.depromeet.piki.folder.repository.FolderWishRepository
import com.depromeet.piki.wishlist.service.FolderEntry
import com.depromeet.piki.wishlist.service.WishFolderPort
import org.springframework.stereotype.Component
import java.util.UUID

// 위시 목록 조회가 폴더에 묻는 것을 답한다. 잠그지 않는 읽기만 한다
@Component
class WishFolderAdapter(
    private val folderRepository: FolderRepository,
    private val folderWishRepository: FolderWishRepository,
) : WishFolderPort {
    override fun entriesInFolder(
        userId: UUID,
        folderId: Long,
        afterPosition: Long?,
        limit: Int,
    ): List<FolderEntry> {
        requireOwned(userId, folderId)
        return folderWishRepository
            .findLivePositions(folderId, afterPosition, limit)
            .map { FolderEntry(position = it.position, wishId = it.wishId) }
    }

    override fun wishIdsInFolder(
        userId: UUID,
        folderId: Long,
        among: Collection<Long>,
    ): Set<Long> {
        requireOwned(userId, folderId)
        return folderWishRepository.findWishIdsByFolderIdAmong(folderId, among)
    }

    override fun countInFolder(
        userId: UUID,
        folderId: Long,
        excludeFolderId: Long?,
    ): Long {
        requireOwned(userId, folderId)
        excludeFolderId ?: return folderWishRepository.countLiveByFolderId(folderId).toLong()
        requireOwned(userId, excludeFolderId)
        return folderWishRepository.countLiveByFolderIdExcluding(folderId, excludeFolderId)
    }

    override fun folderIdsOf(wishIds: Collection<Long>): Map<Long, List<Long>> = folderWishRepository.findFolderIdsByWishIds(wishIds)

    private fun requireOwned(
        userId: UUID,
        folderId: Long,
    ) {
        folderRepository.findOwned(folderId, userId) ?: throw FolderException.notFound()
    }
}
