package com.depromeet.piki.folder.repository

import com.depromeet.piki.folder.domain.FolderWish
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface FolderWishJpaRepository : JpaRepository<FolderWish, Long> {
    // 지워진 위시를 가리키는 연결도 포함한다 — 다시 넣을 때 유니크 키와 부딪히지 않게 "이미 있음" 으로 본다
    @Query("SELECT fw.wishId FROM FolderWish fw WHERE fw.folderId = :folderId")
    fun findWishIdsByFolderId(
        @Param("folderId") folderId: Long,
    ): List<Long>

    // 화면에 보이는 연결. 지워진 위시는 거른다(위시 삭제가 폴더를 몰라도 되게). 최근 넣은 순
    @Query(
        "SELECT fw.folderId AS folderId, fw.wishId AS wishId FROM FolderWish fw, Wish w " +
            "WHERE w.id = fw.wishId AND w.deletedAt IS NULL AND fw.folderId IN :folderIds " +
            "ORDER BY fw.folderId ASC, fw.createdAt DESC, fw.id DESC",
    )
    fun findLiveEntriesByFolderIds(
        @Param("folderIds") folderIds: Collection<Long>,
    ): List<FolderWishEntryView>

    // 폴더 안 위시 목록 한 페이지. 연결 id 가 넣은 순서라(자동 증가) 그대로 커서로 쓴다
    @Query(
        "SELECT fw.id AS position, fw.wishId AS wishId FROM FolderWish fw, Wish w " +
            "WHERE w.id = fw.wishId AND w.deletedAt IS NULL AND fw.folderId = :folderId ORDER BY fw.id DESC",
    )
    fun findLivePositionsByFolderId(
        @Param("folderId") folderId: Long,
        limit: Limit,
    ): List<FolderWishPositionView>

    @Query(
        "SELECT fw.id AS position, fw.wishId AS wishId FROM FolderWish fw, Wish w " +
            "WHERE w.id = fw.wishId AND w.deletedAt IS NULL AND fw.folderId = :folderId AND fw.id < :after ORDER BY fw.id DESC",
    )
    fun findLivePositionsByFolderIdAfter(
        @Param("folderId") folderId: Long,
        @Param("after") after: Long,
        limit: Limit,
    ): List<FolderWishPositionView>

    // 주어진 위시 중 이 폴더에 든 것. 유니크 키(folder_id, wish_id)로 찾는다
    @Query("SELECT fw.wishId FROM FolderWish fw WHERE fw.folderId = :folderId AND fw.wishId IN :wishIds")
    fun findWishIdsByFolderIdAndWishIdIn(
        @Param("folderId") folderId: Long,
        @Param("wishIds") wishIds: Collection<Long>,
    ): List<Long>

    // 위시마다 들어 있는 폴더. 폴더를 지우면 연결도 지우므로 연결만 보면 된다
    @Query("SELECT fw.folderId AS folderId, fw.wishId AS wishId FROM FolderWish fw WHERE fw.wishId IN :wishIds ORDER BY fw.folderId ASC")
    fun findByWishIdIn(
        @Param("wishIds") wishIds: Collection<Long>,
    ): List<FolderWishEntryView>

    @Query(
        "SELECT COUNT(fw) FROM FolderWish fw, Wish w " +
            "WHERE w.id = fw.wishId AND w.deletedAt IS NULL AND fw.folderId = :folderId",
    )
    fun countLiveByFolderId(
        @Param("folderId") folderId: Long,
    ): Long

    @Modifying
    @Query("DELETE FROM FolderWish fw WHERE fw.folderId = :folderId AND fw.wishId IN :wishIds")
    fun deleteByFolderIdAndWishIdIn(
        @Param("folderId") folderId: Long,
        @Param("wishIds") wishIds: Collection<Long>,
    ): Int

    @Modifying
    @Query("DELETE FROM FolderWish fw WHERE fw.folderId = :folderId")
    fun deleteAllByFolderId(
        @Param("folderId") folderId: Long,
    ): Int

    // 탈퇴 cascade — 그 유저 폴더의 연결 전부. 폴더보다 먼저 지운다
    @Modifying
    @Query("DELETE FROM FolderWish fw WHERE fw.folderId IN (SELECT f.id FROM Folder f WHERE f.userId = :userId)")
    fun hardDeleteAllByUserId(
        @Param("userId") userId: UUID,
    ): Int
}
