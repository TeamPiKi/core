package com.depromeet.piki.tournament.repository

import com.depromeet.piki.tournament.domain.Tournament
import com.depromeet.piki.tournament.domain.TournamentHistory
import com.depromeet.piki.tournament.domain.TournamentStatus
import java.time.LocalDateTime
import org.springframework.stereotype.Repository

@Repository
class TournamentRepositoryImpl(
    private val tournamentJpaRepository: TournamentJpaRepository,
    private val tournamentHistoryJpaRepository: TournamentHistoryJpaRepository,
) : TournamentRepository {
    override fun saveTournament(tournament: Tournament): Tournament = tournamentJpaRepository.save(tournament)

    override fun saveHistory(history: TournamentHistory) {
        tournamentHistoryJpaRepository.save(history)
    }

    override fun findTournamentById(tournamentId: Long): Tournament? =
        tournamentJpaRepository.findByIdAndDeletedAtIsNull(tournamentId)

    override fun findTournamentByIdForUpdate(tournamentId: Long): Tournament? =
        tournamentJpaRepository.findByIdForUpdate(tournamentId)

    override fun findHistoriesByTournamentIdAndTournamentUserId(
        tournamentId: Long,
        tournamentUserId: Long,
    ): List<TournamentHistory> =
        tournamentHistoryJpaRepository
            .findAllByTournamentIdAndTournamentUserIdAndDeletedAtIsNullOrderByCurrentRoundAscIdAsc(
                tournamentId, tournamentUserId,
            )

    override fun findHistoriesByTournamentIds(ids: List<Long>): List<TournamentHistory> =
        if (ids.isEmpty()) emptyList()
        else tournamentHistoryJpaRepository.findAllByTournamentIdInAndDeletedAtIsNull(ids)

    override fun findByIdsAndStatuses(
        ids: List<Long>,
        statuses: List<TournamentStatus>?,
    ): List<Tournament> {
        if (ids.isEmpty()) return emptyList()
        return statuses
            ?.takeIf { it.isNotEmpty() }
            ?.let { tournamentJpaRepository.findByIdInAndStatusInAndDeletedAtIsNullOrderByCreatedAtDesc(ids, it) }
            ?: tournamentJpaRepository.findByIdInAndDeletedAtIsNullOrderByCreatedAtDesc(ids)
    }

    override fun findBySourceTournamentId(sourceTournamentId: Long): List<Tournament> =
        tournamentJpaRepository.findBySourceTournamentIdAndDeletedAtIsNull(sourceTournamentId)

    override fun findTournamentByInviteCode(code: String): Tournament? =
        tournamentJpaRepository.findFirstByActiveInviteCode(code)

    override fun existsTournamentByInviteCode(code: String): Boolean =
        tournamentJpaRepository.existsByActiveInviteCode(code)

    override fun softDeleteTournament(tournamentId: Long) {
        tournamentJpaRepository.softDeleteById(tournamentId, LocalDateTime.now())
    }
}
