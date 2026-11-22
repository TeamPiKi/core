package com.depromeet.piki.folder.service

import com.depromeet.piki.folder.domain.FolderContents
import com.depromeet.piki.folder.domain.FolderException
import com.depromeet.piki.folder.domain.FolderWish
import com.depromeet.piki.folder.repository.FolderRepository
import com.depromeet.piki.folder.repository.FolderWishRepository
import com.depromeet.piki.wishlist.domain.WishFolderResult
import com.depromeet.piki.wishlist.service.WishFolderLinker
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

// 위시를 담으며 폴더에 넣는 연결(#1266). 위시 도메인이 정의한 포트를 폴더 도메인이 구현한다
@Component
class FolderWishLinker(
    private val folderRepository: FolderRepository,
    private val folderWishRepository: FolderWishRepository,
) : WishFolderLinker {
    @Transactional(readOnly = true)
    override fun requireOwned(
        userId: UUID,
        folderId: Long,
    ) {
        folderRepository.findOwned(folderId, userId) ?: throw FolderException.notFound()
    }

    // 위시 등록 트랜잭션에 합류한다. 그 트랜잭션이 회원 행을 먼저 잠그므로 폴더 쓰기와 잠금 순서가 같다
    @Transactional(propagation = Propagation.MANDATORY)
    override fun link(
        userId: UUID,
        folderId: Long,
        wishId: Long,
    ): WishFolderResult {
        val folder = folderRepository.findOwnedForUpdate(folderId, userId) ?: throw FolderException.notFound()
        val adding = FolderContents(folder.getId(), folderWishRepository.findWishIdsByFolderId(folder.getId())).put(listOf(wishId))
        if (adding.isEmpty()) return WishFolderResult.ALREADY_IN_FOLDER
        folderWishRepository.saveAll(adding.map { FolderWish(folderId = folder.getId(), wishId = it) })
        return WishFolderResult.ADDED
    }
}
