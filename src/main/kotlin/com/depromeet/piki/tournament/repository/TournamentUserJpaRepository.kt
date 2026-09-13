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

    fun existsByTournamentIdAndUserIdAndDeletedAtIsNull(
        tournamentId: Long,
        userId: UUID,
    ): Boolean

    fun findByTournamentIdAndDeletedAtIsNull(tournamentId: Long): List<TournamentUser>

    fun countByTournamentIdAndDeletedAtIsNull(tournamentId: Long): Int

    // 참여 닉네임 전역 유일성 검사용(#1018). 모든 표시명(프로필+참여)이 하나의 전역 네임스페이스라, 활성 TU 중
    // 같은 닉이 이미 있으면 새 참여 닉으로 못 쓴다. NULL(레거시)은 = 비교라 자동 제외된다.
    fun existsByNicknameAndDeletedAtIsNull(nickname: String): Boolean

    // 위와 같되 자기 자신은 제외한다 — 자기 프로필/자기 다른 토너먼트 참여 닉과 같은 값은 허용(#1018 "자기 이름은 항상 허용").
    fun existsByNicknameAndDeletedAtIsNullAndUserIdNot(
        nickname: String,
        userId: UUID,
    ): Boolean

    @Query("SELECT tu FROM TournamentUser tu WHERE tu.tournamentId IN :tournamentIds AND tu.deletedAt IS NULL")
    fun findByTournamentIdInAndNotDeleted(
        @Param("tournamentIds") tournamentIds: Collection<Long>,
    ): List<TournamentUser>

    // deletedAt 필터 없음 — 주최자가 토너먼트를 삭제(TU soft-delete)해도 그룹 결과에서 오너를 역조회할 수 있어야 한다.
    fun findByIdIn(ids: Collection<Long>): List<TournamentUser>

    // status = COMPLETED 기준 — deletedAt 무관(#1027). 완료 판정의 단일 출처를 status 로 통일한다(TournamentUser.isCompleted 와 동일 기준).
    // 삭제한 주최자의 완료 내역도 그룹 결과에 반영해야 하므로 deletedAt 은 안 건다.
    @Query("SELECT tu FROM TournamentUser tu WHERE tu.tournamentId = :tournamentId AND tu.status = com.depromeet.piki.tournament.domain.TournamentStatus.COMPLETED")
    fun findCompletedByTournamentId(@Param("tournamentId") tournamentId: Long): List<TournamentUser>

    @Query("SELECT COUNT(tu) FROM TournamentUser tu WHERE tu.tournamentId = :tournamentId AND tu.status = com.depromeet.piki.tournament.domain.TournamentStatus.COMPLETED")
    fun countCompletedByTournamentId(@Param("tournamentId") tournamentId: Long): Int

    // 목록 카드의 "플레이한 N" 배치 조회(#1062). 단건 findCompletedByTournamentId 와 같은 기준(status=COMPLETED, deletedAt 무관)이다.
    @Query("SELECT tu FROM TournamentUser tu WHERE tu.tournamentId IN :tournamentIds AND tu.status = com.depromeet.piki.tournament.domain.TournamentStatus.COMPLETED")
    fun findCompletedByTournamentIdIn(
        @Param("tournamentIds") tournamentIds: Collection<Long>,
    ): List<TournamentUser>

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
