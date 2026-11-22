package com.depromeet.piki.folder.repository

import com.depromeet.piki.folder.domain.Folder
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface FolderJpaRepository : JpaRepository<Folder, Long> {
    fun findByIdAndUserIdAndDeletedAtIsNull(
        id: Long,
        userId: UUID,
    ): Folder?

    // 넣기·빼기·이동을 폴더 단위로 줄 세운다. 같은 위시를 동시에 넣어 유니크 키에 부딪히는 일을 막는다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM Folder f WHERE f.id = :id AND f.userId = :userId AND f.deletedAt IS NULL")
    fun findOwnedForUpdate(
        @Param("id") id: Long,
        @Param("userId") userId: UUID,
    ): Folder?

    // 위시 합치기가 연결을 옮길 폴더들. id 순으로 잠가 폴더 넣기·옮기기와 같은 순서를 지킨다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM Folder f WHERE f.id IN :ids ORDER BY f.id ASC")
    fun findAllByIdsForUpdate(
        @Param("ids") ids: Collection<Long>,
    ): List<Folder>

    fun findByUserIdAndDeletedAtIsNullOrderByIdAsc(userId: UUID): List<Folder>

    // 탈퇴 cascade — 그 유저의 폴더를 영구 하드삭제. 멱등(없으면 0건)
    @Modifying
    @Query("DELETE FROM Folder f WHERE f.userId = :userId")
    fun hardDeleteAllByUserId(
        @Param("userId") userId: UUID,
    ): Int
}
