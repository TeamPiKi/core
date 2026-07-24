package com.depromeet.piki.tournament.repository

import com.depromeet.piki.tournament.domain.TournamentUser
import java.time.LocalDateTime
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface TournamentUserJpaRepository : JpaRepository<TournamentUser, Long> {
    fun findByTournamentIdAndUserIdAndDeletedAtIsNull(
        tournamentId: Long,
        userId: UUID,
    ): TournamentUser?

    fun findByTournamentIdAndDeletedAtIsNull(tournamentId: Long): List<TournamentUser>

    fun countByTournamentIdAndDeletedAtIsNull(tournamentId: Long): Int

    @Query("SELECT tu FROM TournamentUser tu WHERE tu.tournamentId IN :tournamentIds AND tu.deletedAt IS NULL")
    fun findByTournamentIdInAndNotDeleted(
        @Param("tournamentIds") tournamentIds: Collection<Long>,
    ): List<TournamentUser>

    // deletedAt 필터 없음 — 주최자가 토너먼트를 삭제(TU soft-delete)해도 그룹 결과에서 오너를 역조회할 수 있어야 한다.
    fun findByIdIn(ids: Collection<Long>): List<TournamentUser>

    // completedAt 기준 — deletedAt 무관. 삭제한 주최자의 완료 내역도 그룹 결과에 반영해야 한다.
    @Query("SELECT tu FROM TournamentUser tu WHERE tu.tournamentId = :tournamentId AND tu.completedAt IS NOT NULL")
    fun findCompletedByTournamentId(@Param("tournamentId") tournamentId: Long): List<TournamentUser>

    @Query("SELECT COUNT(tu) FROM TournamentUser tu WHERE tu.tournamentId = :tournamentId AND tu.completedAt IS NOT NULL")
    fun countCompletedByTournamentId(@Param("tournamentId") tournamentId: Long): Int

    @Modifying
    @Query("UPDATE TournamentUser tu SET tu.deletedAt = :now WHERE tu.tournamentId = :tournamentId AND tu.userId = :userId AND tu.deletedAt IS NULL")
    fun softDeleteByTournamentIdAndUserId(
        @Param("tournamentId") tournamentId: Long,
        @Param("userId") userId: UUID,
        @Param("now") now: LocalDateTime,
    )

    @Modifying
    @Query("UPDATE TournamentUser tu SET tu.deletedAt = :now WHERE tu.tournamentId = :tournamentId AND tu.deletedAt IS NULL")
    fun softDeleteAllByTournamentId(
        @Param("tournamentId") tournamentId: Long,
        @Param("now") now: LocalDateTime,
    )
}
