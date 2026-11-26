package com.depromeet.piki.explore.domain

import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals

class ExploreFeedTest {
    private val now = LocalDateTime.of(2026, 11, 23, 10, 0)

    @Test
    fun `같은 상품을 여러 명이 담으면 가장 최근에 담긴 한 장만 내준다`() {
        val feed =
            ExploreFeed.of(
                listOf(card(wishId = 1, itemId = 10), card(wishId = 3, itemId = 10), card(wishId = 2, itemId = 20)),
                itemLimit = 10,
                builtAt = now,
            )

        assertEquals(listOf(3L, 2L), feed.page(afterWishId = null, size = 10).map { it.wishId })
    }

    @Test
    fun `가장 최근에 담은 사람을 가리면 같은 상품을 담은 다음 사람의 카드를 내준다`() {
        val hidden = UUID.randomUUID()
        val feed =
            ExploreFeed.of(
                listOf(
                    card(wishId = 3, itemId = 10, ownerId = hidden),
                    card(wishId = 2, itemId = 20),
                    card(wishId = 1, itemId = 10),
                ),
                itemLimit = 10,
                builtAt = now,
            )

        val page = feed.page(afterWishId = null, size = 10, hiddenOwnerIds = setOf(hidden))

        assertEquals(listOf(2L, 1L), page.map { it.wishId })
    }

    @Test
    fun `앞 쪽에 나온 상품은 더 일찍 담긴 카드로 다음 쪽에 다시 나오지 않는다`() {
        val feed =
            ExploreFeed.of(
                listOf(card(wishId = 3, itemId = 10), card(wishId = 2, itemId = 20), card(wishId = 1, itemId = 10)),
                itemLimit = 10,
                builtAt = now,
            )

        assertEquals(listOf(3L, 2L), feed.page(afterWishId = null, size = 2).map { it.wishId })
        assertEquals(emptyList(), feed.page(afterWishId = 2, size = 2).map { it.wishId })
    }

    @Test
    fun `최근에 담긴 상품부터 상한 개수의 상품만 남기고 그 상품의 카드는 전부 둔다`() {
        val feed =
            ExploreFeed.of(
                listOf(
                    card(wishId = 4, itemId = 40),
                    card(wishId = 3, itemId = 30),
                    card(wishId = 2, itemId = 20),
                    card(wishId = 1, itemId = 40),
                ),
                itemLimit = 2,
                builtAt = now,
            )

        assertEquals(listOf(4L, 3L, 1L), feed.cards.map { it.wishId })
    }

    @Test
    fun `커서보다 먼저 담긴 카드부터 크기만큼 내준다`() {
        val feed = ExploreFeed.of((1L..5L).map { card(wishId = it, itemId = it) }, itemLimit = 10, builtAt = now)

        assertEquals(listOf(5L, 4L), feed.page(afterWishId = null, size = 2).map { it.wishId })
        assertEquals(listOf(3L, 2L), feed.page(afterWishId = 4, size = 2).map { it.wishId })
        assertEquals(listOf(1L), feed.page(afterWishId = 2, size = 2).map { it.wishId })
    }

    @Test
    fun `가릴 사람의 카드는 건너뛰고 크기를 채운다`() {
        val hidden = UUID.randomUUID()
        val feed =
            ExploreFeed.of(
                listOf(
                    card(wishId = 4, itemId = 40),
                    card(wishId = 3, itemId = 30, ownerId = hidden),
                    card(wishId = 2, itemId = 20),
                    card(wishId = 1, itemId = 10),
                ),
                itemLimit = 10,
                builtAt = now,
            )

        val page = feed.page(afterWishId = null, size = 2, hiddenOwnerIds = setOf(hidden))

        assertEquals(listOf(4L, 2L), page.map { it.wishId })
    }

    private fun card(
        wishId: Long,
        itemId: Long,
        ownerId: UUID = UUID.randomUUID(),
    ): ExploreCard =
        ExploreCard(
            wishId = wishId,
            ownerId = ownerId,
            itemId = itemId,
            addedAt = now,
            name = "상품",
            price = 10_000,
            originalPrice = null,
            discountRate = null,
            currency = "KRW",
            imageUrl = "https://img.example.com/a.png",
            sourceUrl = null,
            sourcePlatform = null,
        )
}
