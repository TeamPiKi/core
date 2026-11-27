package com.depromeet.piki.explore.repository

import com.depromeet.piki.tournament.domain.TournamentHistory
import com.depromeet.piki.tournament.domain.TournamentItem
import com.depromeet.piki.user.domain.IdentityType
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param

// 1위는 따로 저장되지 않는다. 참여자마다 결승에서 고른 후보가 그 사람의 1위다
interface ExplorePickJpaRepository : Repository<TournamentHistory, Long> {
    // 공개 단위는 고른 사람의 계정이다. 게스트는 프로필이 없어 공개를 끌 수 없으므로 뺀다.
    // 차단은 보는 사람마다 달라 여기서 거르지 않는다
    @Query(
        "SELECT h.id AS pickId, tu.userId AS pickerId, t.id AS tournamentId, t.name AS tournamentName, " +
            "ti.id AS candidateId, ti.itemId AS itemId, h.createdAt AS pickedAt " +
            "FROM TournamentHistory h, TournamentUser tu, User u, Tournament t, TournamentItem ti " +
            "WHERE h.currentRound = :finalRound AND h.deletedAt IS NULL " +
            "AND tu.id = h.tournamentUserId AND tu.deletedAt IS NULL " +
            "AND u.id = tu.userId AND u.deletedAt IS NULL AND u.isProfilePublic = true AND u.identityType = :member " +
            "AND t.id = h.tournamentId AND t.deletedAt IS NULL " +
            "AND ti.id = h.selectedTournamentItemId AND ti.tournamentId = t.id AND ti.deletedAt IS NULL AND ti.itemId IS NOT NULL " +
            "ORDER BY h.id DESC",
    )
    fun findExposedLatest(
        @Param("finalRound") finalRound: Int,
        @Param("member") member: IdentityType,
        limit: Limit,
    ): List<ExplorePickRow>

    // 고른 사람을 보여도 되는지는 보는 사람에 따라 달라 호출부가 판정한다. 후보 목록과 같은 토너먼트의 후보만 1위로 본다
    @Query(
        "SELECT h.id AS pickId, tu.userId AS pickerId, t.id AS tournamentId, t.name AS tournamentName, " +
            "ti.id AS candidateId, ti.itemId AS itemId, h.createdAt AS pickedAt " +
            "FROM TournamentHistory h, TournamentUser tu, Tournament t, TournamentItem ti " +
            "WHERE h.id = :pickId AND h.currentRound = :finalRound AND h.deletedAt IS NULL " +
            "AND tu.id = h.tournamentUserId AND tu.deletedAt IS NULL " +
            "AND t.id = h.tournamentId AND t.deletedAt IS NULL " +
            "AND ti.id = h.selectedTournamentItemId AND ti.tournamentId = t.id AND ti.deletedAt IS NULL AND ti.itemId IS NOT NULL",
    )
    fun findPick(
        @Param("pickId") pickId: Long,
        @Param("finalRound") finalRound: Int,
    ): ExplorePickRow?

    @Query(
        "SELECT ti FROM TournamentItem ti WHERE ti.tournamentId = :tournamentId AND ti.deletedAt IS NULL " +
            "AND ti.itemId IS NOT NULL ORDER BY ti.id ASC",
    )
    fun findCandidates(
        @Param("tournamentId") tournamentId: Long,
    ): List<TournamentItem>
}
