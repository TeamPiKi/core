package com.depromeet.piki.tournament.repository

import com.depromeet.piki.tournament.domain.Tournament
import com.depromeet.piki.tournament.domain.TournamentHistory
import com.depromeet.piki.tournament.domain.TournamentPlayType
import com.depromeet.piki.tournament.domain.TournamentStatus
import java.time.LocalDateTime
import java.util.UUID
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
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

    override fun findVisibleByUserId(
        userId: UUID,
        statuses: List<TournamentStatus>?,
        playType: TournamentPlayType?,
        limit: Int?,
    ): List<Tournament> =
        tournamentJpaRepository.findVisibleByUserId(
            userId = userId,
            // status 컬럼이 NOT NULL 이라 "전체 상태 IN" 과 "필터 없음" 이 동치다. 쿼리를 2벌로 나누지 않기 위해 전체를 바인딩한다.
            statuses = statuses?.takeIf { it.isNotEmpty() } ?: TournamentStatus.entries,
            // playType 미지정(null)이면 두 플래그가 다 TRUE 가 되어 파생 술어가 항상 성립한다(= 필터 없음).
            // statuses 를 전체 바인딩하는 것과 같은 방식으로, nullable 파라미터를 쿼리에 넘기지 않는다.
            includeSolo = playType != TournamentPlayType.SOCIAL,
            includeSocial = playType != TournamentPlayType.SOLO,
            pageable = limit?.let { PageRequest.of(0, it) } ?: Pageable.unpaged(),
        )

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
