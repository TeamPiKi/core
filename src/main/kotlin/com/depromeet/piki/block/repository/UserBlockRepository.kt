package com.depromeet.piki.block.repository

import com.depromeet.piki.block.domain.UserBlock
import java.util.UUID

interface UserBlockRepository {
    fun save(block: UserBlock): UserBlock

    fun exists(
        userId: UUID,
        blockedId: UUID,
    ): Boolean

    // 지운 행 수. 없으면 0 — 해제는 멱등이라 호출자가 0 을 실패로 보지 않는다
    fun delete(
        userId: UUID,
        blockedId: UUID,
    ): Int

    // 최근 차단순
    fun findAllByUserId(userId: UUID): List<UserBlock>

    // 어느 방향이든 차단이 있으면 true
    fun existsBetween(
        a: UUID,
        b: UUID,
    ): Boolean

    // 내가 차단했거나 나를 차단한 상대 전부
    fun findCounterpartIds(userId: UUID): Set<UUID>

    fun hardDeleteAllInvolving(userId: UUID): Int
}
