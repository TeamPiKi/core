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

    // 참여 닉네임 전역 유일성 검사(#1018) — 활성 TU 중 같은 닉이 있는지. userId 오버로드는 자기 자신을 제외한다.
    fun existsByNickname(nickname: String): Boolean

    fun existsByNicknameExcludingUser(
        nickname: String,
        excludeUserId: UUID,
    ): Boolean

    fun findByTournamentIds(tournamentIds: List<Long>): List<TournamentUser>

    fun findByIds(ids: Collection<Long>): List<TournamentUser>

    fun softDeleteByTournamentIdAndUserId(tournamentId: Long, userId: UUID)

    fun softDeleteAllByTournamentId(tournamentId: Long)

    fun countCompletedByTournamentId(tournamentId: Long): Int

    // deletedAt 무관 — 삭제한 주최자의 완료 내역도 그룹 결과에 반영해야 한다.
    fun findCompletedByTournamentId(tournamentId: Long): List<TournamentUser>

    // 위와 같은 기준의 배치 조회 — 목록 카드가 여러 토너먼트의 "플레이한 N" 을 한 번에 센다(#1062).
    fun findCompletedByTournamentIds(tournamentIds: List<Long>): List<TournamentUser>

    // 이 유저의 활성 참여 행 전부 — 게스트 플레이 승계(#1081)가 옮길 대상을 모은다.
    fun findByUserId(userId: UUID): List<TournamentUser>

    // 이 유저가 이미 자리를 잡은 토너먼트 id — 승계 시 uk_tournament_users(tournament_id, user_id) 충돌을 미리 가른다.
    // deletedAt 무관인 이유는 유니크 키에 deleted_at 이 없어 soft-delete 된 행도 그 자리를 계속 점유하기 때문이다.
    fun findTournamentIdsByUserIdIncludingDeleted(userId: UUID): List<Long>

    // 참여 행의 주인을 옮긴다(#1081). tournament_users.id 가 그대로라 히스토리(tournament_user_id)와
    // 방장 지정(tournaments.owner_tournament_user_id)이 자동으로 따라온다 — 재지향할 자식이 없다.
    fun transferToUser(
        fromUserId: UUID,
        toUserId: UUID,
        tournamentIds: List<Long>,
    ): Int

    fun softDeleteByUserIdAndTournamentIds(
        userId: UUID,
        tournamentIds: List<Long>,
    ): Int
}
