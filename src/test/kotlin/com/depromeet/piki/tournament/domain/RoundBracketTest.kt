package com.depromeet.piki.tournament.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoundBracketTest {
    // 가격이 서로 다른 n 개 아이템. id 는 1..n, 가격은 id 순 증가라 "가격 정렬 = id 정렬" 이 되어 인접성 검증이 쉽다.
    private fun entries(count: Int): List<RoundBracket.Entry> =
        (1..count).map { RoundBracket.Entry(tournamentItemId = it.toLong(), price = it * 1_000) }

    private fun bracket(
        count: Int,
        tournamentUserId: Long = 7L,
        round: Int = count,
    ): RoundBracket = RoundBracket.of(entries(count), tournamentUserId, round)

    @ParameterizedTest(name = "인원 {0} → 매치 {1}, 부전승 {2}")
    @CsvSource(
        "32, 16, 0",
        "25, 9, 7",
        "16, 8, 0",
        "12, 4, 4",
        "8, 4, 0",
        "7, 3, 1",
        "5, 1, 3",
        "4, 2, 0",
        "3, 1, 1",
        "2, 1, 0",
    )
    fun `매치 수와 부전승 수는 2의 거듭제곱 정규화 공식을 따른다`(
        playerCount: Int,
        expectedMatches: Int,
        expectedByes: Int,
    ) {
        val bracket = bracket(playerCount)
        assertEquals(expectedMatches, bracket.pairs.size)
        assertEquals(expectedByes, bracket.byeTournamentItemIds.size)
    }

    @ParameterizedTest(name = "인원 {0}")
    @ValueSource(ints = [2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32])
    fun `인원 2에서 32까지 모든 아이템이 매치 또는 부전승에 정확히 한 번 배정된다`(playerCount: Int) {
        val bracket = bracket(playerCount)
        val assigned = bracket.pairs.flatMap { listOf(it.first, it.second) } + bracket.byeTournamentItemIds
        assertEquals(playerCount, assigned.size, "배정 총량이 인원과 다르다")
        assertEquals(playerCount, assigned.toSet().size, "같은 아이템이 두 번 배정됐다")
        assertEquals((1L..playerCount).toSet(), assigned.toSet())
    }

    @ParameterizedTest(name = "인원 {0}")
    @ValueSource(ints = [3, 5, 6, 7, 9, 10, 11, 12, 13, 14, 15, 17, 20, 25, 30, 31])
    fun `2의 거듭제곱이 아닌 인원은 첫 라운드 통과 인원이 2의 거듭제곱이 된다`(playerCount: Int) {
        val bracket = bracket(playerCount)
        val survivors = bracket.pairs.size + bracket.byeTournamentItemIds.size
        assertTrue(
            RoundBracket.isPowerOfTwo(survivors),
            "인원 $playerCount 의 다음 라운드가 $survivors 명 — 2의 거듭제곱이 아니다",
        )
        assertEquals(Integer.highestOneBit(playerCount), survivors)
    }

    @ParameterizedTest(name = "인원 {0}")
    @ValueSource(ints = [2, 4, 8, 16, 32])
    fun `2의 거듭제곱 인원은 부전승 없이 절반씩 진행한다`(playerCount: Int) {
        val bracket = bracket(playerCount)
        assertEquals(playerCount / 2, bracket.pairs.size)
        assertTrue(bracket.byeTournamentItemIds.isEmpty())
    }

    @ParameterizedTest(name = "인원 {0}")
    @ValueSource(ints = [2, 3, 5, 7, 8, 12, 16, 25, 32])
    fun `부전승을 제외한 나머지는 가격 인접 페어로 묶인다`(playerCount: Int) {
        val bracket = bracket(playerCount)
        // id 순 = 가격 순. 부전승을 뺀 뒤 인접 2개씩 묶은 것이 파생 페어와 정확히 같아야 한다.
        val expected = (1L..playerCount)
            .filterNot { it in bracket.byeTournamentItemIds }
            .chunked(2)
            .map { setOf(it[0], it[1]) }
            .toSet()
        val actual = bracket.pairs.map { setOf(it.first, it.second) }.toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun `가격이 동률이면 tournamentItemId 오름차순으로 인접 페어를 묶는다`() {
        val sameprice = (1..4).map { RoundBracket.Entry(tournamentItemId = it.toLong(), price = 10_000) }
        val bracket = RoundBracket.of(sameprice, tournamentUserId = 1L, round = 4)
        assertEquals(
            setOf(setOf(1L, 2L), setOf(3L, 4L)),
            bracket.pairs.map { setOf(it.first, it.second) }.toSet(),
        )
    }

    @Test
    fun `price 가 null 인 아이템은 null-first 로 정렬된다`() {
        val withNull = listOf(
            RoundBracket.Entry(tournamentItemId = 1L, price = 5_000),
            RoundBracket.Entry(tournamentItemId = 2L, price = null),
            RoundBracket.Entry(tournamentItemId = 3L, price = 1_000),
            RoundBracket.Entry(tournamentItemId = 4L, price = null),
        )
        val bracket = RoundBracket.of(withNull, tournamentUserId = 1L, round = 4)
        // 정렬 결과는 [2(null), 4(null), 3(1000), 1(5000)] → 인접 페어는 (2,4) · (3,1)
        assertEquals(
            setOf(setOf(2L, 4L), setOf(3L, 1L)),
            bracket.pairs.map { setOf(it.first, it.second) }.toSet(),
        )
    }

    @ParameterizedTest(name = "인원 {0}")
    @ValueSource(ints = [3, 5, 12, 25, 32])
    fun `같은 tournamentUserId 와 round 로 파생하면 진행 순서와 부전승이 그대로 재현된다`(playerCount: Int) {
        val first = RoundBracket.of(entries(playerCount), tournamentUserId = 42L, round = playerCount)
        val second = RoundBracket.of(entries(playerCount), tournamentUserId = 42L, round = playerCount)
        assertEquals(first.pairs, second.pairs)
        assertEquals(first.byeTournamentItemIds, second.byeTournamentItemIds)
    }

    @Test
    fun `입력 순서가 뒤섞여도 같은 브래킷이 파생된다`() {
        val ordered = entries(25)
        val shuffled = ordered.reversed()
        val fromOrdered = RoundBracket.of(ordered, tournamentUserId = 3L, round = 25)
        val fromShuffled = RoundBracket.of(shuffled, tournamentUserId = 3L, round = 25)
        assertEquals(fromOrdered.pairs, fromShuffled.pairs)
        assertEquals(fromOrdered.byeTournamentItemIds, fromShuffled.byeTournamentItemIds)
    }

    @Test
    fun `tournamentUserId 가 다르면 진행 순서가 달라진다`() {
        val mine = RoundBracket.of(entries(16), tournamentUserId = 1L, round = 16)
        val theirs = RoundBracket.of(entries(16), tournamentUserId = 99L, round = 16)
        assertTrue(mine.pairs != theirs.pairs, "참여자가 달라도 진행 순서가 같다 — seed 가 반영되지 않았다")
    }

    @Test
    fun `라운드가 다르면 진행 순서가 달라진다`() {
        val entries = entries(8)
        val round8 = RoundBracket.of(entries, tournamentUserId = 5L, round = 8)
        val round4 = RoundBracket.of(entries, tournamentUserId = 5L, round = 4)
        assertTrue(round8.pairs != round4.pairs, "라운드가 달라도 진행 순서가 같다 — seed 가 반영되지 않았다")
    }

    @Test
    fun `contains 는 좌우가 뒤집힌 조합도 같은 매치로 인정한다`() {
        val bracket = bracket(8)
        val pair = bracket.pairs.first()
        assertTrue(bracket.contains(pair.first, pair.second))
        assertTrue(bracket.contains(pair.second, pair.first))
    }

    @Test
    fun `파생 집합에 없는 조합은 contains 가 거부한다`() {
        // 가격 정렬이 id 정렬과 같으므로 (1,8) 은 인접이 아니라 브래킷에 존재할 수 없다.
        val bracket = bracket(8)
        assertFalse(bracket.contains(1L, 8L))
    }

    @Test
    fun `firstUnplayed 는 아직 치르지 않은 첫 매치를 진행 순서대로 반환한다`() {
        val bracket = bracket(8)
        assertEquals(bracket.pairs[0], bracket.firstUnplayed(emptyList()))
        assertEquals(bracket.pairs[1], bracket.firstUnplayed(listOf(bracket.pairs[0])))
        // 좌우가 뒤집혀 기록됐어도 치른 매치로 인정한다.
        val flipped = bracket.pairs[0].let { RoundBracket.MatchPair(it.second, it.first) }
        assertEquals(bracket.pairs[1], bracket.firstUnplayed(listOf(flipped)))
    }

    @Test
    fun `모든 매치를 치르면 firstUnplayed 는 null 이다`() {
        val bracket = bracket(8)
        assertNull(bracket.firstUnplayed(bracket.pairs))
    }

    @Test
    fun `결승 인원 미만은 불변식 위반이다`() {
        // 정상 흐름은 라운드 수학이 2 에서 멈추므로 여기 닿지 않는다 — 닿았다면 호출부 버그다.
        assertFailsWith<IllegalArgumentException> { RoundBracket.matchCountOf(1) }
        assertFailsWith<IllegalArgumentException> { RoundBracket.matchCountOf(0) }
    }
}
