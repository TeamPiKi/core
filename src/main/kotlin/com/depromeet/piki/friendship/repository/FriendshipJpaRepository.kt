package com.depromeet.piki.friendship.repository

import com.depromeet.piki.friendship.domain.Friendship
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface FriendshipJpaRepository : JpaRepository<Friendship, Long> {
    fun existsByUserIdAndFriendId(
        userId: UUID,
        friendId: UUID,
    ): Boolean

    fun deleteByUserIdAndFriendId(
        userId: UUID,
        friendId: UUID,
    ): Int

    // 최근 추가순. soft delete 가 없어 deletedAt 가드가 필요 없다
    fun findByUserIdOrderByIdDesc(userId: UUID): List<Friendship>

    fun countByUserId(userId: UUID): Long

    // 탈퇴 cascade — 그 사용자가 들어간 행을 양방향으로 지운다. 추가한 쪽도, 추가된 쪽도 남기지 않는다
    @Modifying
    @Query("DELETE FROM Friendship f WHERE f.userId = :userId OR f.friendId = :userId")
    fun hardDeleteAllInvolving(
        @Param("userId") userId: UUID,
    ): Int
}
