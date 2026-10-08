package com.depromeet.piki.tournament.domain

import com.depromeet.piki.item.domain.ItemSlot
import com.depromeet.piki.item.domain.CardQuery
import com.depromeet.piki.support.withId
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TournamentItemTest {
    @Test
    fun `정상 ID 로 생성되면 필드가 그대로 보존된다`() {
        val userId = UUID.randomUUID()

        val tournamentItem = TournamentItem(tournamentId = 1, userId = userId, itemId = 2)

        assertEquals(1, tournamentItem.tournamentId)
        assertEquals(2, tournamentItem.itemId)
        assertEquals(userId, tournamentItem.userId)
    }

    @Test
    fun `파싱 성공 전 출전 아이템은 아이템 없이 생성된다`() {
        assertNull(TournamentItem(tournamentId = 1, userId = UUID.randomUUID(), itemId = null).itemId)
    }

    @ParameterizedTest
    @ValueSource(longs = [0, -1])
    fun `tournamentId 가 양수가 아니면 생성이 거부된다`(invalidId: Long) {
        assertFailsWith<IllegalArgumentException> {
            TournamentItem(tournamentId = invalidId, userId = UUID.randomUUID(), itemId = 1)
        }
    }

    @ParameterizedTest
    @ValueSource(longs = [0, -1])
    fun `itemId 가 양수가 아니면 생성이 거부된다`(invalidId: Long) {
        assertFailsWith<IllegalArgumentException> {
            TournamentItem(tournamentId = 1, userId = UUID.randomUUID(), itemId = invalidId)
        }
    }

    @Test
    fun `fill 은 비어 있던 아이템을 한 번만 확정한다`() {
        val tournamentItem = TournamentItem(tournamentId = 1, userId = UUID.randomUUID(), itemId = null)

        tournamentItem.fill(3L)
        tournamentItem.fill(3L)

        assertEquals(3L, tournamentItem.itemId)
        assertFailsWith<IllegalStateException> { tournamentItem.fill(4L) }
    }

    @Test
    fun `cardQuery 는 시작 시각을 기준 시각으로 담는다`() {
        val owner = UUID.randomUUID()
        val startedAt = LocalDateTime.of(2026, 10, 1, 12, 0)
        val tournamentItem = TournamentItem(tournamentId = 1, userId = owner, itemId = 3L).withId(9L)

        assertEquals(
            CardQuery(slot = ItemSlot.tournamentItem(9L), itemId = 3L, owner = owner, asOf = startedAt),
            tournamentItem.cardQuery(startedAt),
        )
    }
}
