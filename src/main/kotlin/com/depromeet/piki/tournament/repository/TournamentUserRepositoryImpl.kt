package com.depromeet.piki.tournament.repository

import com.depromeet.piki.tournament.domain.TournamentUser
import java.time.LocalDateTime
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class TournamentUserRepositoryImpl(
    private val tournamentUserJpaRepository: TournamentUserJpaRepository,
) : TournamentUserRepository {
    override fun save(tournamentUser: TournamentUser): TournamentUser = tournamentUserJpaRepository.save(tournamentUser)

    override fun findByTournamentIdAndUserId(
        tournamentId: Long,
        userId: UUID,
    ): TournamentUser? = tournamentUserJpaRepository.findByTournamentIdAndUserIdAndDeletedAtIsNull(tournamentId, userId)

    override fun existsByTournamentIdAndUserId(
        tournamentId: Long,
        userId: UUID,
    ): Boolean = tournamentUserJpaRepository.existsByTournamentIdAndUserIdAndDeletedAtIsNull(tournamentId, userId)

    override fun findByTournamentId(tournamentId: Long): List<TournamentUser> =
        tournamentUserJpaRepository.findByTournamentIdAndDeletedAtIsNull(tournamentId)

    override fun countByTournamentId(tournamentId: Long): Int =
        tournamentUserJpaRepository.countByTournamentIdAndDeletedAtIsNull(tournamentId)

    override fun existsByNickname(nickname: String): Boolean =
        tournamentUserJpaRepository.existsByNicknameAndDeletedAtIsNull(nickname)

    override fun existsByNicknameExcludingUser(
        nickname: String,
        excludeUserId: UUID,
    ): Boolean = tournamentUserJpaRepository.existsByNicknameAndDeletedAtIsNullAndUserIdNot(nickname, excludeUserId)

    override fun findByTournamentIds(tournamentIds: List<Long>): List<TournamentUser> =
        if (tournamentIds.isEmpty()) {
            emptyList()
        } else {
            tournamentUserJpaRepository.findByTournamentIdInAndNotDeleted(tournamentIds)
        }

    override fun findByIds(ids: Collection<Long>): List<TournamentUser> =
        if (ids.isEmpty()) emptyList() else tournamentUserJpaRepository.findByIdIn(ids)

    override fun softDeleteByTournamentIdAndUserId(tournamentId: Long, userId: UUID) {
        tournamentUserJpaRepository.softDeleteByTournamentIdAndUserId(tournamentId, userId, LocalDateTime.now())
    }

    override fun softDeleteAllByTournamentId(tournamentId: Long) {
        tournamentUserJpaRepository.softDeleteAllByTournamentId(tournamentId, LocalDateTime.now())
    }

    override fun countCompletedByTournamentId(tournamentId: Long): Int =
        tournamentUserJpaRepository.countCompletedByTournamentId(tournamentId)

    override fun findCompletedByTournamentId(tournamentId: Long): List<TournamentUser> =
        tournamentUserJpaRepository.findCompletedByTournamentId(tournamentId)
}
