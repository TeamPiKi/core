package com.depromeet.piki.folder.repository

import com.depromeet.piki.folder.domain.Folder
import java.util.UUID

interface FolderRepository {
    fun save(folder: Folder): Folder

    // 남의 폴더는 없는 폴더와 같게 null
    fun findOwned(
        id: Long,
        userId: UUID,
    ): Folder?

    fun findOwnedForUpdate(
        id: Long,
        userId: UUID,
    ): Folder?

    // id 순으로 잠근다
    fun findAllByIdsForUpdate(ids: Collection<Long>): List<Folder>

    // 생성순
    fun findAllByUserId(userId: UUID): List<Folder>

    fun hardDeleteAllByUserId(userId: UUID): Int
}
