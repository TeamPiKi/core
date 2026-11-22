package com.depromeet.piki.block.repository

import com.depromeet.piki.block.domain.UserBlock
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class UserBlockRepositoryImpl(
    private val userBlockJpaRepository: UserBlockJpaRepository,
) : UserBlockRepository {
    override fun save(block: UserBlock): UserBlock = userBlockJpaRepository.save(block)

    override fun exists(
        userId: UUID,
        blockedId: UUID,
    ): Boolean = userBlockJpaRepository.existsByUserIdAndBlockedId(userId, blockedId)

    override fun delete(
        userId: UUID,
        blockedId: UUID,
    ): Int = userBlockJpaRepository.deleteByUserIdAndBlockedId(userId, blockedId)

    override fun findAllByUserId(userId: UUID): List<UserBlock> = userBlockJpaRepository.findByUserIdOrderByIdDesc(userId)

    override fun existsBetween(
        a: UUID,
        b: UUID,
    ): Boolean = userBlockJpaRepository.existsBetween(a, b)

    override fun hardDeleteAllInvolving(userId: UUID): Int = userBlockJpaRepository.hardDeleteAllInvolving(userId)
}
