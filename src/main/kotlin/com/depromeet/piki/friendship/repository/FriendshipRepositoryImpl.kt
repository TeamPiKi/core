package com.depromeet.piki.friendship.repository

import com.depromeet.piki.friendship.domain.Friendship
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class FriendshipRepositoryImpl(
    private val friendshipJpaRepository: FriendshipJpaRepository,
) : FriendshipRepository {
    override fun save(friendship: Friendship): Friendship = friendshipJpaRepository.save(friendship)

    override fun exists(
        userId: UUID,
        friendId: UUID,
    ): Boolean = friendshipJpaRepository.existsByUserIdAndFriendId(userId, friendId)

    override fun delete(
        userId: UUID,
        friendId: UUID,
    ): Int = friendshipJpaRepository.deleteByUserIdAndFriendId(userId, friendId)

    override fun findAllByUserId(userId: UUID): List<Friendship> =
        friendshipJpaRepository.findByUserIdOrderByIdDesc(userId)

    override fun countByUserId(userId: UUID): Long = friendshipJpaRepository.countByUserId(userId)

    override fun hardDeleteAllInvolving(userId: UUID): Int = friendshipJpaRepository.hardDeleteAllInvolving(userId)
}
