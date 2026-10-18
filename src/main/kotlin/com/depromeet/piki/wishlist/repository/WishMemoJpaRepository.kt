package com.depromeet.piki.wishlist.repository

import com.depromeet.piki.wishlist.domain.WishMemo
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface WishMemoJpaRepository : JpaRepository<WishMemo, Long> {
    fun findFirstByWishIdOrderByWrittenAtDescIdDesc(wishId: Long): WishMemo?

    fun findAllByWishId(wishId: Long): List<WishMemo>

    @Modifying
    @Query("DELETE FROM WishMemo m WHERE m.wishId IN (SELECT w.id FROM Wish w WHERE w.userId = :userId)")
    fun hardDeleteAllByUserId(
        @Param("userId") userId: UUID,
    ): Int
}
