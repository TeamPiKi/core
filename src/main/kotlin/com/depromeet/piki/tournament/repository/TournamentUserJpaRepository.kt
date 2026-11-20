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

    // 참여 닉네임은 그 토너먼트 안에서만 유일하다(#1287). 전역 유일(#1018)은 걷어냈다 — 구분이 필요한 범위가
    // 한 토너먼트의 대기실·진행·결과 화면이기 때문이다. NULL(레거시)은 = 비교라 자동 제외된다.
    fun existsByTournamentIdAndNicknameAndDeletedAtIsNull(
        tournamentId: Long,
        nickname: String,
    ): Boolean

    // 위와 같되 자기 자신은 제외한다 — 자기 참여 닉과 같은 값으로 다시 설정하는 흐름이 통과해야 한다.
    fun existsByTournamentIdAndNicknameAndDeletedAtIsNullAndUserIdNot(
        tournamentId: Long,
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

    fun findByUserIdAndDeletedAtIsNull(userId: UUID): List<TournamentUser>

    // 유니크 키(tournament_id, user_id)에 deleted_at 이 없어 soft-delete 된 행도 자리를 계속 점유한다 —
    // 승계 충돌 판정은 활성 행만 봐서는 안 되므로 deletedAt 필터를 두지 않는다(#1081).
    @Query("SELECT tu.tournamentId FROM TournamentUser tu WHERE tu.userId = :userId")
    fun findTournamentIdsByUserId(
        @Param("userId") userId: UUID,
    ): List<Long>

    // 참여 행의 주인만 갈아끼운다(#1081). 행 id 가 유지되므로 히스토리·방장 지정이 그대로 따라온다.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "UPDATE TournamentUser tu SET tu.userId = :toUserId, tu.updatedAt = :now " +
            "WHERE tu.userId = :fromUserId AND tu.tournamentId IN :tournamentIds AND tu.deletedAt IS NULL",
    )
    fun transferToUser(
        @Param("fromUserId") fromUserId: UUID,
        @Param("toUserId") toUserId: UUID,
        @Param("tournamentIds") tournamentIds: Collection<Long>,
        @Param("now") now: LocalDateTime,
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        "UPDATE TournamentUser tu SET tu.deletedAt = :now " +
            "WHERE tu.userId = :userId AND tu.tournamentId IN :tournamentIds AND tu.deletedAt IS NULL",
    )
    fun softDeleteByUserIdAndTournamentIdIn(
        @Param("userId") userId: UUID,
        @Param("tournamentIds") tournamentIds: Collection<Long>,
        @Param("now") now: LocalDateTime,
    ): Int

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
