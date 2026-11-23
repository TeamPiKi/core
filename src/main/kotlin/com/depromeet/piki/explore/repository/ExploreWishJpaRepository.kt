package com.depromeet.piki.explore.repository

import com.depromeet.piki.wishlist.domain.Wish
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ExploreWishJpaRepository : JpaRepository<Wish, Long> {
    // ProfileExposure.canSeeWishInExplore 를 조회 조건으로 옮긴 것이다. 규칙이 바뀌면 둘을 함께 고친다.
    // 차단은 보는 사람마다 달라 여기서 거르지 않는다.
    // STRAIGHT_JOIN 이 없으면 users 부터 읽어 공개 사용자의 위시 전체를 정렬한다(위시 30만 건 EXPLAIN ANALYZE 0.9s, 고정 뒤 3ms)
    @Query(
        value =
            "SELECT w.* FROM wishes w STRAIGHT_JOIN users u ON u.id = w.user_id " +
                "WHERE u.deleted_at IS NULL AND u.is_profile_public = TRUE " +
                "AND w.deleted_at IS NULL AND w.item_id IS NOT NULL " +
                "AND (" +
                "NOT EXISTS (SELECT 1 FROM folder_wishes fw JOIN folders f ON f.id = fw.folder_id " +
                "WHERE fw.wish_id = w.id AND f.deleted_at IS NULL) " +
                "OR EXISTS (SELECT 1 FROM folder_wishes pfw JOIN folders pf ON pf.id = pfw.folder_id " +
                "WHERE pfw.wish_id = w.id AND pf.deleted_at IS NULL AND pf.is_public = TRUE)" +
                ") " +
                "ORDER BY w.id DESC LIMIT :limit",
        nativeQuery = true,
    )
    fun findExposedLatest(
        @Param("limit") limit: Int,
    ): List<Wish>
}
