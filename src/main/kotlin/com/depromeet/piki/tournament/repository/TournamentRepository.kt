package com.depromeet.piki.tournament.repository

import com.depromeet.piki.tournament.domain.Tournament
import com.depromeet.piki.tournament.domain.TournamentHistory
import com.depromeet.piki.tournament.domain.TournamentPlayType
import com.depromeet.piki.tournament.domain.TournamentStatus
import java.util.UUID

interface TournamentRepository {
    fun saveTournament(tournament: Tournament): Tournament

    fun saveHistory(history: TournamentHistory)

    fun findTournamentById(tournamentId: Long): Tournament?

    fun findTournamentByIdForUpdate(tournamentId: Long): Tournament?

    fun findHistoriesByTournamentIdAndTournamentUserId(
        tournamentId: Long,
        tournamentUserId: Long,
    ): List<TournamentHistory>

    fun findHistoriesByTournamentIds(ids: List<Long>): List<TournamentHistory>

    // 목록 화면용 — 내가 멤버인 토너먼트 중 "보이는 것"만 최근순 limit 개.
    // 가시성 필터·정렬·limit 을 DB 가 처리하므로 호출부는 남은 것에 대해서만 참가자·썸네일을 읽으면 된다.
    fun findVisibleByUserId(
        userId: UUID,
        statuses: List<TournamentStatus>?,
        playType: TournamentPlayType?,
        limit: Int?,
    ): List<Tournament>

    fun findBySourceTournamentId(sourceTournamentId: Long): List<Tournament>

    fun findTournamentByInviteCode(code: String): Tournament?

    fun existsTournamentByInviteCode(code: String): Boolean

    fun softDeleteTournament(tournamentId: Long)
}
