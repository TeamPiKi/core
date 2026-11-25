package com.depromeet.piki.explore.repository

import com.depromeet.piki.folder.domain.Folder
import com.depromeet.piki.wishlist.domain.Wish
import com.depromeet.piki.wishlist.repository.WishCardView
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface ExploreWishJpaRepository : JpaRepository<Wish, Long> {
    // 공개 단위는 계정 하나다. 폴더는 노출에 관여하지 않는다.
    // 차단은 보는 사람마다 달라 여기서 거르지 않는다.
    // STRAIGHT_JOIN 이 없으면 users 부터 읽어 공개 사용자의 위시 전체를 정렬한다(위시 30만 건 EXPLAIN ANALYZE 0.9s, 고정 뒤 3ms)
    @Query(
        value =
            "SELECT w.* FROM wishes w STRAIGHT_JOIN users u ON u.id = w.user_id " +
                "WHERE u.deleted_at IS NULL AND u.is_profile_public = TRUE " +
                "AND w.deleted_at IS NULL AND w.item_id IS NOT NULL " +
                "ORDER BY w.id DESC LIMIT :limit",
        nativeQuery = true,
    )
    fun findExposedLatest(
        @Param("limit") limit: Int,
    ): List<Wish>

    // 가장 최근에 넣은 순. 비공개 폴더는 이름과 id 를 남에게 보이지 않아 여기서 뺀다
    @Query(
        "SELECT f FROM FolderWish fw, Folder f " +
            "WHERE fw.wishId = :wishId AND f.id = fw.folderId AND f.userId = :ownerId " +
            "AND f.deletedAt IS NULL AND f.isPublic = true " +
            "ORDER BY fw.id DESC",
    )
    fun findPublicFoldersOf(
        @Param("wishId") wishId: Long,
        @Param("ownerId") ownerId: UUID,
    ): List<Folder>

    @Query(
        "SELECT w FROM Wish w WHERE w.userId IN :ownerIds AND w.deletedAt IS NULL AND w.itemId IS NOT NULL " +
            "AND w.id < :beforeWishId ORDER BY w.id DESC",
    )
    fun findLatestOfOwners(
        @Param("ownerIds") ownerIds: Collection<UUID>,
        @Param("beforeWishId") beforeWishId: Long,
        limit: Limit,
    ): List<Wish>

    @Query(
        "SELECT w.userId AS userId, MAX(w.id) AS wishId FROM Wish w " +
            "WHERE w.userId IN :ownerIds AND w.deletedAt IS NULL AND w.itemId IS NOT NULL GROUP BY w.userId",
    )
    fun findLatestWishIdByOwner(
        @Param("ownerIds") ownerIds: Collection<UUID>,
    ): List<WishCardView>

    @Query("SELECT COUNT(w) FROM Wish w WHERE w.itemId = :itemId AND w.deletedAt IS NULL")
    fun countLiveByItemId(
        @Param("itemId") itemId: Long,
    ): Long
}
