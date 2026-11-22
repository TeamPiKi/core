package com.depromeet.piki.block.repository

import com.depromeet.piki.block.domain.UserBlock
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface UserBlockJpaRepository : JpaRepository<UserBlock, Long> {
    fun existsByUserIdAndBlockedId(
        userId: UUID,
        blockedId: UUID,
    ): Boolean

    fun deleteByUserIdAndBlockedId(
        userId: UUID,
        blockedId: UUID,
    ): Int

    // 최근 차단순. soft delete 가 없어 deletedAt 가드가 필요 없다
    fun findByUserIdOrderByIdDesc(userId: UUID): List<UserBlock>

    // 어느 방향이든 차단이 있으면 true. 노출 판정과 친구 추가 거부가 쓴다 —
    // 차단은 효과가 양방향이라 "누가 걸었는지" 를 묻지 않는 자리가 있다
    @Query(
        "SELECT COUNT(b) > 0 FROM UserBlock b " +
            "WHERE (b.userId = :a AND b.blockedId = :b) OR (b.userId = :b AND b.blockedId = :a)",
    )
    fun existsBetween(
        @Param("a") a: UUID,
        @Param("b") b: UUID,
    ): Boolean

    // 탈퇴 cascade — 그 사용자가 들어간 행을 양방향으로 지운다
    @Modifying
    @Query("DELETE FROM UserBlock b WHERE b.userId = :userId OR b.blockedId = :userId")
    fun hardDeleteAllInvolving(
        @Param("userId") userId: UUID,
    ): Int
}
