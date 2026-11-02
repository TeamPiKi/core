package com.depromeet.piki.folder.repository

import com.depromeet.piki.folder.domain.Folder
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class FolderRepositoryImpl(
    private val folderJpaRepository: FolderJpaRepository,
) : FolderRepository {
    override fun save(folder: Folder): Folder = folderJpaRepository.save(folder)

    override fun findOwned(
        id: Long,
        userId: UUID,
    ): Folder? = folderJpaRepository.findByIdAndUserIdAndDeletedAtIsNull(id, userId)

    override fun findOwnedForUpdate(
        id: Long,
        userId: UUID,
    ): Folder? = folderJpaRepository.findOwnedForUpdate(id, userId)

    override fun findAllByUserId(userId: UUID): List<Folder> = folderJpaRepository.findByUserIdAndDeletedAtIsNullOrderByIdAsc(userId)

    override fun hardDeleteAllByUserId(userId: UUID): Int = folderJpaRepository.hardDeleteAllByUserId(userId)
}
