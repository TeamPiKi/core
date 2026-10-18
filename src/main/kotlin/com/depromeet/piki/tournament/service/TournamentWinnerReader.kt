package com.depromeet.piki.tournament.service

import com.depromeet.piki.tournament.domain.Tournament
import com.depromeet.piki.tournament.repository.TournamentHistoryJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * "이 사용자가 우승시킨 상품" 을 읽는다. 위시 목록의 우승 칩(#1236)이 쓴다.
 *
 * 토너먼트 바깥(위시)에서 필요한 질문이지만 판정 규칙은 토너먼트의 것이라 여기 둔다. 결승 라운드 번호가
 * Tournament 의 internal 상수인 것도 같은 이유다 — 그 값을 호출자가 알아야 한다면 경계가 샌 것이다.
 *
 * TournamentService 에 얹지 않는다. 그쪽은 플레이 진행·결과 집계를 맡는 큰 서비스라, 조회 한 줄을 더하면
 * 읽는 사람이 "우승 칩도 저 안 어딘가" 를 뒤져야 한다. 질문이 작으므로 자리도 작게 둔다.
 */
@Service
class TournamentWinnerReader(
    private val tournamentHistoryJpaRepository: TournamentHistoryJpaRepository,
) {
    /**
     * [itemIds] 중 이 사용자가 **자기 플레이의 결승에서 고른** 상품들.
     *
     * 호출자가 관심 있는 상품만 넘기고 그 부분집합을 돌려받는다. 한 페이지에 보이는 것만 묻게 되어
     * 위시가 수백 건이어도 비용이 페이지 크기에 묶인다.
     *
     * 빈 입력에 쿼리를 날리지 않는다 — `IN ()` 은 DB 마다 문법이 갈리고, 어차피 답이 공집합이다.
     */
    @Transactional(readOnly = true)
    fun findWinnerItemIds(
        userId: UUID,
        itemIds: Collection<Long>,
    ): Set<Long> {
        if (itemIds.isEmpty()) return emptySet()
        return tournamentHistoryJpaRepository
            .findWinnerItemIds(userId, itemIds, Tournament.FINAL_ROUND_SIZE)
            .toSet()
    }
}
