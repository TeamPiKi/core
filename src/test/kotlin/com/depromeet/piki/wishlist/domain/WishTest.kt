package com.depromeet.piki.wishlist.domain

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WishTest {
    @Test
    fun `swapSnapshot 은 활성 포인터를 새 snapshot id 로 바꾼다`() {
        // 수동 새로고침(5단계) — 새 추출 버전으로 활성 포인터를 교체한다. 옛 snapshot 행은 유지(토너먼트 출전 격리).
        val wish = Wish(userId = UUID.randomUUID(), snapshotId = 10L)
        wish.swapSnapshot(20L)
        assertEquals(20L, wish.snapshotId)
    }

    @Test
    fun `swapSnapshot 에 0 이하 id 를 주면 실패한다`() {
        val wish = Wish(userId = UUID.randomUUID(), snapshotId = 10L)
        assertFailsWith<IllegalArgumentException> { wish.swapSnapshot(0L) }
        assertFailsWith<IllegalArgumentException> { wish.swapSnapshot(-1L) }
    }

    @Test
    fun `snapshotId 가 0 이하면 생성에 실패한다`() {
        assertFailsWith<IllegalArgumentException> { Wish(userId = UUID.randomUUID(), snapshotId = 0L) }
        assertFailsWith<IllegalArgumentException> { Wish(userId = UUID.randomUUID(), snapshotId = -1L) }
    }

    @Test
    fun `verifyOwnedBy 는 소유자가 아니면 WishException 을 던진다`() {
        val owner = UUID.randomUUID()
        val wish = Wish(userId = owner, snapshotId = 10L)
        assertFailsWith<WishException> { wish.verifyOwnedBy(UUID.randomUUID()) }
    }

    @Test
    fun `verifyOwnedBy 는 소유자가 호출하면 예외 없이 통과한다`() {
        val owner = UUID.randomUUID()
        val wish = Wish(userId = owner, snapshotId = 10L)
        wish.verifyOwnedBy(owner) // 소유자면 예외 없이 반환된다
    }

    @Test
    fun `updateMemo 는 앞뒤 공백을 정리해 메모를 저장한다`() {
        val wish = Wish(userId = UUID.randomUUID(), snapshotId = 10L)
        wish.updateMemo("  생일 선물 후보  ")
        assertEquals("생일 선물 후보", wish.memo)
    }

    @Test
    fun `updateMemo 에 빈 문자열이나 공백만 주면 메모가 삭제된다`() {
        val wish = Wish(userId = UUID.randomUUID(), snapshotId = 10L)
        wish.updateMemo("메모")
        wish.updateMemo("   ")
        assertNull(wish.memo)
    }

    @Test
    fun `updateMemo 는 100자까지 허용하고 초과하면 실패한다`() {
        val wish = Wish(userId = UUID.randomUUID(), snapshotId = 10L)
        wish.updateMemo("가".repeat(100))
        assertEquals(100, wish.memo?.length)
        assertFailsWith<IllegalArgumentException> { wish.updateMemo("가".repeat(101)) }
    }
}
