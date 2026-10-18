package com.depromeet.piki.tournament.repository

import com.depromeet.piki.tournament.domain.TournamentHistory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface TournamentHistoryJpaRepository : JpaRepository<TournamentHistory, Long> {
    fun findAllByTournamentIdAndTournamentUserIdAndDeletedAtIsNullOrderByCurrentRoundAscIdAsc(
        tournamentId: Long,
        tournamentUserId: Long,
    ): List<TournamentHistory>

    fun findAllByTournamentIdInAndDeletedAtIsNull(tournamentIds: List<Long>): List<TournamentHistory>

    /**
     * 이 사용자가 **자기 플레이의 결승에서 고른** 상품들(itemId). 위시 목록의 우승 칩 판정에 쓴다(#1236).
     *
     * 기준이 참여 행(TournamentUser)인 것이 핵심이다. 그룹 합산 순위가 아니라 "내가 선택한 1등" 이므로,
     * 같은 소셜 토너먼트라도 사람마다 답이 다르다. 결승 한 행만 보면 되어 순위 전체를 계산할 필요가 없다
     * (TournamentService.computeRanking 은 2·3위까지 필요할 때만 쓴다).
     *
     * 삭제된 참여 행은 빠진다 — 토너먼트를 지우면 칩도 사라져야 한다는 기획 요구가 여기서 충족된다.
     * 우승을 따로 저장하지 않고 매 조회에 계산하는 이유도 이것이다. 저장하면 삭제와 동기화하는 부채가 생긴다.
     *
     * 출전(TournamentItem)은 상품을 버전(snapshot)으로 가리키므로 itemId 까지 두 단계를 더 탄다.
     * FK·연관관계를 두지 않는 규약이라 암시적 조인으로 엮는다(TournamentItemJpaRepository 의 선례와 같은 형태).
     *
     * itemIds 로 먼저 좁혀, 한 페이지에 보이는 상품만 본다. 위시가 수백 건이어도 조회 비용이 페이지 크기에 묶인다.
     */
    @Query(
        "SELECT DISTINCT s.itemId FROM TournamentHistory h, TournamentUser tu, TournamentItem ti, ItemSnapshot s " +
            "WHERE h.tournamentUserId = tu.id AND tu.userId = :userId AND tu.deletedAt IS NULL " +
            "AND h.currentRound = :finalRound AND h.deletedAt IS NULL " +
            "AND ti.id = h.selectedTournamentItemId AND ti.deletedAt IS NULL " +
            "AND s.id = ti.snapshotId AND s.itemId IN :itemIds",
    )
    fun findWinnerItemIds(
        @Param("userId") userId: UUID,
        @Param("itemIds") itemIds: Collection<Long>,
        @Param("finalRound") finalRound: Int,
    ): List<Long>
}
