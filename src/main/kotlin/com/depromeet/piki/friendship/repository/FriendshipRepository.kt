package com.depromeet.piki.friendship.repository

import com.depromeet.piki.friendship.domain.Friendship
import java.util.UUID

interface FriendshipRepository {
    fun save(friendship: Friendship): Friendship

    fun exists(
        userId: UUID,
        friendId: UUID,
    ): Boolean

    // 지운 행 수. 없으면 0 — 삭제는 멱등이라 호출자가 0 을 실패로 보지 않는다
    fun delete(
        userId: UUID,
        friendId: UUID,
    ): Int

    // 최근 추가순
    fun findAllByUserId(userId: UUID): List<Friendship>

    fun countByUserId(userId: UUID): Long

    // 탈퇴 cascade — 그 사용자가 들어간 행을 양방향으로 제거
    fun hardDeleteAllInvolving(userId: UUID): Int
}
