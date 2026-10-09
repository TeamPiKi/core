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
    fun `cardQuery 는 시작한 토너먼트의 시작 시각을 기준 시각으로 담고 시작 전이면 기준 시각이 없다`() {
        val owner = UUID.randomUUID()
        val tournament = tournament().withId(1L)
        val tournamentItem = TournamentItem(tournamentId = 1, userId = owner, itemId = 3L).withId(9L)

        assertNull(tournamentItem.cardQuery(tournament).asOf)
        tournament.start()
        assertEquals(
            CardQuery(slot = ItemSlot.tournamentItem(9L), itemId = 3L, owner = owner, asOf = tournament.startedAt),
            tournamentItem.cardQuery(tournament),
        )
    }

    @Test
    fun `다른 토너먼트로 cardQuery 를 만들면 코드 버그로 본다`() {
        val tournamentItem = TournamentItem(tournamentId = 1, userId = UUID.randomUUID(), itemId = 3L).withId(9L)

        assertFailsWith<IllegalArgumentException> { tournamentItem.cardQuery(tournament().withId(2L)) }
    }

    private fun tournament(): Tournament =
        Tournament(
            ownerTournamentUserId = 0L,
            name = "테스트",
            inviteCode = "ABC123",
            inviteExpiresAt = LocalDateTime.now().plusMinutes(30),
        )
}
