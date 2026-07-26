package com.depromeet.piki.tournament.repository

import com.depromeet.piki.tournament.domain.TournamentUser
import java.util.UUID

interface TournamentUserRepository {
    fun save(tournamentUser: TournamentUser): TournamentUser

    fun findByTournamentIdAndUserId(
        tournamentId: Long,
        userId: UUID,
    ): TournamentUser?

    // 참여 여부만 필요한 preview 용 — soft-delete(탈퇴) 된 참여는 제외한다.
    fun existsByTournamentIdAndUserId(
        tournamentId: Long,
        userId: UUID,
    ): Boolean

    fun findByTournamentId(tournamentId: Long): List<TournamentUser>

    fun countByTournamentId(tournamentId: Long): Int

    fun findByTournamentIds(tournamentIds: List<Long>): List<TournamentUser>

    fun findByIds(ids: Collection<Long>): List<TournamentUser>

    fun softDeleteByTournamentIdAndUserId(tournamentId: Long, userId: UUID)

    fun softDeleteAllByTournamentId(tournamentId: Long)

    fun countCompletedByTournamentId(tournamentId: Long): Int

    // deletedAt 무관 — 삭제한 주최자의 완료 내역도 그룹 결과에 반영해야 한다.
    fun findCompletedByTournamentId(tournamentId: Long): List<TournamentUser>
}
