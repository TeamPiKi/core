package com.depromeet.piki.explore.repository

import com.depromeet.piki.wishlist.domain.Wish
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

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
}
