package com.depromeet.piki.tournament.domain

import kotlin.random.Random

// 한 라운드의 매치 구성을 파생한다 (이슈 #683). 스키마 없이 (라운드 시작 시점 아이템, tournamentUserId, round) 만으로
// 결정론적으로 재현되므로, 새로고침해도 같은 순서·같은 부전승이 복원된다.
//
// 브래킷은 첫 라운드에서 2의 거듭제곱으로 정규화된다. 이후 라운드 인원은 항상 2의 거듭제곱이라
// 부전승이 다시 생기지 않고, currentRound 를 라벨에 그대로 쓰는 클라가 "16강·8강·4강·결승" 으로 맞아떨어진다.
class RoundBracket private constructor(
    // 진행 순서까지 정해진 매치 목록. 라운드 내 매치는 서로 독립이라 순서는 결과에 영향을 주지 않는다.
    val pairs: List<MatchPair>,
    // 이 라운드를 싸우지 않고 통과하는 아이템들. 가격 편향을 없애려 시드 랜덤으로 고른다.
    val byeTournamentItemIds: List<Long>,
) {
    // 파생 입력. price 는 정렬 기준이며 nullable 이다 (가격 미확정 아이템은 시작 단계에서 걸리지만,
    // 도메인은 경계 검증에 의존하지 않고 null-first 정렬로 스스로 정의된 동작을 갖는다).
    data class Entry(
        val tournamentItemId: Long,
        val price: Int?,
    )

    data class MatchPair(
        val first: Long,
        val second: Long,
    ) {
        // 좌/우가 뒤집혀 와도 같은 매치다. 클라가 어느 쪽을 first 로 보내든 조합 자체가 브래킷의 본질이다.
        fun isSamePair(
            a: Long,
            b: Long,
        ): Boolean = (first == a && second == b) || (first == b && second == a)
    }

    fun contains(
        first: Long,
        second: Long,
    ): Boolean = pairs.any { it.isSamePair(first, second) }

    // 아직 치르지 않은 첫 매치. 진행 순서는 파생 시점에 이미 시드 셔플로 확정돼 있다.
    fun firstUnplayed(playedPairs: Collection<MatchPair>): MatchPair? =
        pairs.firstOrNull { pair -> playedPairs.none { it.isSamePair(pair.first, pair.second) } }

    companion object {
        fun of(
            entries: List<Entry>,
            tournamentUserId: Long,
            round: Int,
        ): RoundBracket {
            val matchCount = matchCountOf(entries.size)
            val byeCount = entries.size - matchCount * 2
            // 가격 인접 페어가 브래킷의 성질이다 — 정렬 순서를 유지한 채 인접 2개씩 묶는다.
            val sorted = entries.sortedWith(compareBy({ it.price }, { it.tournamentItemId }))
            val random = Random(seedOf(tournamentUserId, round))
            // 같은 Random 인스턴스를 순차 사용하므로 부전승 선정과 진행 순서 셔플이 함께 결정론이다.
            val byeIds = sorted
                .shuffled(random)
                .take(byeCount)
                .map { it.tournamentItemId }
            // 부전승을 먼저 빼고 남은 것을 묶어야 가격이 가까운 것끼리 붙는 성질이 유지된다.
            val fighters = sorted.filterNot { it.tournamentItemId in byeIds }
            val pairs = fighters
                .chunked(2)
                .map { (first, second) -> MatchPair(first.tournamentItemId, second.tournamentItemId) }
            return RoundBracket(pairs = pairs.shuffled(random), byeTournamentItemIds = byeIds)
        }

        // seed 는 저장하지 않고 (참여자, 라운드) 로부터 매번 재구성한다. 참여자마다 순서가 다르고
        // 같은 참여자의 같은 라운드는 항상 같은 순서다.
        fun seedOf(
            tournamentUserId: Long,
            round: Int,
        ): Long = tournamentUserId + round

        // 이 라운드에서 치러야 할 매치 수.
        //   n 이 2의 거듭제곱  → n/2, 부전승 0
        //   아니면 target = 2^floor(log2(n)) → 매치 n - target, 부전승 2*target - n
        // 2의 거듭제곱 분기가 없으면 n - n = 0 이 되어 아무도 안 싸우고 라운드가 넘어간다.
        fun matchCountOf(playerCount: Int): Int {
            require(playerCount >= Tournament.FINAL_ROUND_SIZE) {
                "라운드 인원은 최소 ${Tournament.FINAL_ROUND_SIZE} 명 — playerCount=$playerCount"
            }
            if (isPowerOfTwo(playerCount)) return playerCount / 2
            return playerCount - Integer.highestOneBit(playerCount)
        }

        fun isPowerOfTwo(value: Int): Boolean = value > 0 && (value and (value - 1)) == 0
    }
}
