package com.depromeet.piki.tournament.service

import com.depromeet.piki.tournament.service.TournamentThumbnails.Candidate
import kotlin.test.Test
import kotlin.test.assertEquals

class TournamentThumbnailsTest {
    @Test
    fun `최근 등록순으로 정렬해 이미지 있는 후보 최대 2장을 뽑는다`() {
        // recency 가 클수록 최근 등록. 입력은 정렬돼 있지 않다.
        val candidates =
            listOf(
                Candidate(recency = 6, imageUrl = "c.jpg"),
                Candidate(recency = 10, imageUrl = "a.jpg"),
                Candidate(recency = 8, imageUrl = "b.jpg"),
            )

        assertEquals(listOf("a.jpg", "b.jpg"), TournamentThumbnails.select(candidates))
    }

    @Test
    fun `이미지가 null 이거나 빈 문자열인 후보는 제외한다`() {
        val candidates =
            listOf(
                Candidate(recency = 10, imageUrl = null),
                Candidate(recency = 9, imageUrl = ""),
                Candidate(recency = 8, imageUrl = "  "),
                Candidate(recency = 7, imageUrl = "ok.jpg"),
            )

        assertEquals(listOf("ok.jpg"), TournamentThumbnails.select(candidates))
    }

    @Test
    fun `이미지 있는 후보가 없으면 빈 배열을 반환한다`() {
        val candidates =
            listOf(
                Candidate(recency = 2, imageUrl = null),
                Candidate(recency = 1, imageUrl = null),
            )

        assertEquals(emptyList(), TournamentThumbnails.select(candidates))
    }

    @Test
    fun `후보가 없으면 빈 배열을 반환한다`() {
        assertEquals(emptyList(), TournamentThumbnails.select(emptyList()))
    }

    @Test
    fun `이미지 있는 후보가 1장뿐이면 1장만 반환한다`() {
        val candidates = listOf(Candidate(recency = 5, imageUrl = "only.jpg"))

        assertEquals(listOf("only.jpg"), TournamentThumbnails.select(candidates))
    }
}
