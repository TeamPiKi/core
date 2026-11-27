package com.depromeet.piki.explore.domain

import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals

class ExplorePickFeedTest {
    private val now = LocalDateTime.of(2026, 11, 27, 10, 0)

    @Test
    fun `같은 상품이 여러 번 1위가 되면 가장 최근의 한 장만 내준다`() {
        val feed =
            ExplorePickFeed.of(
                listOf(pick(pickId = 1, itemId = 10), pick(pickId = 3, itemId = 10), pick(pickId = 2, itemId = 20)),
                itemLimit = 10,
                builtAt = now,
            )

        assertEquals(listOf(3L, 2L), feed.page(afterPickId = null, size = 10).map { it.pickId })
    }

    @Test
    fun `가장 최근에 고른 사람을 가리면 같은 상품을 고른 다음 사람의 1위를 내준다`() {
        val hidden = UUID.randomUUID()
        val feed =
            ExplorePickFeed.of(
                listOf(pick(pickId = 3, itemId = 10, pickerId = hidden), pick(pickId = 2, itemId = 20), pick(pickId = 1, itemId = 10)),
                itemLimit = 10,
                builtAt = now,
            )

        assertEquals(listOf(2L, 1L), feed.page(afterPickId = null, size = 10, hiddenPickerIds = setOf(hidden)).map { it.pickId })
    }

    @Test
    fun `앞 쪽에 나온 상품은 더 일찍 고른 1위로 다음 쪽에 다시 나오지 않는다`() {
        val feed =
            ExplorePickFeed.of(
                listOf(pick(pickId = 3, itemId = 10), pick(pickId = 2, itemId = 20), pick(pickId = 1, itemId = 10)),
                itemLimit = 10,
                builtAt = now,
            )

        assertEquals(listOf(3L, 2L), feed.page(afterPickId = null, size = 2).map { it.pickId })
        assertEquals(emptyList(), feed.page(afterPickId = 2, size = 2).map { it.pickId })
    }

    @Test
    fun `최근 1위 상품부터 상한 개수의 상품만 남기고 그 상품의 기록은 전부 둔다`() {
        val feed =
            ExplorePickFeed.of(
                listOf(pick(pickId = 4, itemId = 40), pick(pickId = 3, itemId = 30), pick(pickId = 2, itemId = 40), pick(pickId = 1, itemId = 10)),
                itemLimit = 2,
                builtAt = now,
            )

        assertEquals(listOf(4L, 3L, 2L), feed.picks.map { it.pickId })
    }

    private fun pick(
        pickId: Long,
        itemId: Long,
        pickerId: UUID = UUID.randomUUID(),
    ): ExplorePick =
        ExplorePick(
            pickId = pickId,
            pickerId = pickerId,
            tournamentId = 1,
            tournamentName = "토너먼트",
            candidateId = pickId * 10,
            pickedAt = now,
            item =
                ExploreItem(
                    itemId = itemId,
                    name = "상품",
                    price = 1_000,
                    originalPrice = null,
                    discountRate = null,
                    currency = "KRW",
                    imageUrl = "https://img.example.com/p.png",
                    sourceUrl = null,
                    sourcePlatform = null,
                ),
        )
}
