package com.depromeet.piki.item.domain

import com.depromeet.piki.support.withId
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ItemVersionsTest {
    private val me = UUID.randomUUID()
    private val other = UUID.randomUUID()

    @Test
    fun `내 수기값은 최신 서버 READY 보다 새로우면 이긴다`() {
        val ready = machineReady(1, by = other)
        val myManual = manual(2, by = me)

        assertEquals(myManual, display(ready, myManual))
    }

    @Test
    fun `내 수기값보다 새로운 서버 READY 가 생기면 누가 새로고침했든 서버값으로 돌아간다`() {
        val myManual = manual(1, by = me)
        val newerReady = machineReady(2, by = other)

        assertEquals(newerReady, display(myManual, newerReady))
    }

    @Test
    fun `남의 수기값은 내 카드에 보이지 않는다`() {
        val ready = machineReady(1, by = other)
        val othersManual = manual(2, by = other)

        assertEquals(ready, display(ready, othersManual))
    }

    @Test
    fun `내 새로고침이 INCOMPLETE 로 끝나면 옛 READY 대신 일부만 빈 값을 보인다`() {
        val ready = machineReady(1, by = me)
        val myIncomplete = incomplete(2, by = me)

        assertEquals(myIncomplete, display(ready, myIncomplete))
    }

    @Test
    fun `남의 새로고침이 INCOMPLETE 로 끝나도 내 카드는 READY 를 그대로 본다`() {
        val ready = machineReady(1, by = me)
        val othersIncomplete = incomplete(2, by = other)

        assertEquals(ready, display(ready, othersIncomplete))
    }

    @Test
    fun `내 INCOMPLETE 보다 새로운 서버 READY 가 생기면 서버값을 본다`() {
        val myIncomplete = incomplete(1, by = me)
        val newerReady = machineReady(2, by = other)

        assertEquals(newerReady, display(myIncomplete, newerReady))
    }

    @Test
    fun `내 값도 공유 값도 없으면 남의 수기가 아닌 최신 버전을 본다`() {
        val othersIncomplete = incomplete(1, by = other)
        val othersManual = manual(2, by = other)

        assertEquals(othersIncomplete, display(othersIncomplete, othersManual))
    }

    @Test
    fun `수기 버전뿐인 상품은 남의 수기라도 보여 빈 카드가 되지 않는다`() {
        val othersManual = manual(1, by = other)

        assertEquals(othersManual, display(othersManual))
    }

    // 추정 백필이 옛 행에 만든 사람을 채우므로 공유 여부는 출처로만 가른다
    @Test
    fun `출처를 모르는 도입 전 READY 는 남이 만들었어도 공유 값이다`() {
        val myIncomplete = incomplete(1, by = me)
        val legacy = version(2, ready = true, source = null, by = other)

        assertEquals(legacy, display(myIncomplete, legacy))
    }

    @Test
    fun `내 INCOMPLETE 를 보던 카드는 남의 서버 READY 가 생기면 채워진다`() {
        val myIncomplete = incomplete(1, by = me)
        val othersReady = machineReady(2, by = other)

        assertTrue(versions(myIncomplete, othersReady).recovers(me, snapshotId = 2))
    }

    @Test
    fun `내 값 없이 남의 INCOMPLETE 를 보던 카드도 서버 READY 가 생기면 채워진다`() {
        val othersIncomplete = incomplete(1, by = other)
        val othersReady = machineReady(2, by = other)

        assertTrue(versions(othersIncomplete, othersReady).recovers(me, snapshotId = 2))
    }

    @Test
    fun `첫 버전은 해소가 아니라 그 버전을 요청한 카드의 완료다`() {
        val ready = machineReady(1, by = me)

        assertFalse(versions(ready).recovers(me, snapshotId = 1))
    }

    @Test
    fun `이미 READY 를 보던 카드는 남의 새 READY 로 바뀌어도 채워진 것이 아니다`() {
        val oldReady = machineReady(1, by = me)
        val newReady = machineReady(2, by = other)

        assertFalse(versions(oldReady, newReady).recovers(me, snapshotId = 2))
    }

    @Test
    fun `내 수기값이 떠 있던 카드는 서버 READY 가 생겨도 채워진 것이 아니다`() {
        val myManual = manual(1, by = me)
        val othersReady = machineReady(2, by = other)

        assertFalse(versions(myManual, othersReady).recovers(me, snapshotId = 2))
    }

    @Test
    fun `남의 INCOMPLETE 는 내 INCOMPLETE 카드를 채우지 않는다`() {
        val myIncomplete = incomplete(1, by = me)
        val othersIncomplete = incomplete(2, by = other)

        assertFalse(versions(myIncomplete, othersIncomplete).recovers(me, snapshotId = 2))
    }

    // 비동기 디스패치로 P1 처리 시점에 P2 가 이미 있어도 P1 이 채운 것이고 P2 는 이미 값을 보던 카드다
    @Test
    fun `성공 버전이 연달아 쌓여도 채운 버전 한 번만 해소로 본다`() {
        val myIncomplete = incomplete(1, by = me)
        val p1 = machineReady(2, by = other)
        val p2 = machineReady(3, by = other)
        val versions = versions(myIncomplete, p1, p2)

        assertTrue(versions.recovers(me, snapshotId = 2))
        assertFalse(versions.recovers(me, snapshotId = 3))
    }

    @Test
    fun `판정 대상 버전이 이 상품에 없으면 코드 버그다`() {
        assertFailsWith<IllegalArgumentException> {
            versions(machineReady(1, by = me)).recovers(me, snapshotId = 99)
        }
    }

    @Test
    fun `버전이 없으면 카드가 될 수 없다`() {
        assertFailsWith<IllegalArgumentException> { ItemVersions.of(emptyList()) }
    }

    @Test
    fun `다른 상품의 버전은 한데 묶을 수 없다`() {
        val a = machineReady(1, by = me)
        val b = version(2, ready = true, source = ItemSnapshotSource.SERVER, by = me, itemId = 2L)

        assertFailsWith<IllegalArgumentException> { ItemVersions.of(listOf(a, b)) }
    }

    private fun versions(vararg snapshots: ItemSnapshot): ItemVersions = ItemVersions.of(snapshots.toList())

    private fun display(vararg snapshots: ItemSnapshot): ItemSnapshot = versions(*snapshots).displayFor(me)

    private fun machineReady(
        id: Long,
        by: UUID,
        createdAt: LocalDateTime = LocalDateTime.now(),
    ): ItemSnapshot = version(id, ready = true, source = ItemSnapshotSource.SERVER, by = by, createdAt = createdAt)

    private fun manual(
        id: Long,
        by: UUID,
        createdAt: LocalDateTime = LocalDateTime.now(),
    ): ItemSnapshot = version(id, ready = true, source = ItemSnapshotSource.MANUAL, by = by, createdAt = createdAt)

    private fun incomplete(
        id: Long,
        by: UUID,
    ): ItemSnapshot = version(id, ready = false, source = ItemSnapshotSource.SERVER, by = by)

    // "최신" 판정이 id 순서라 영속화 없이 id 와 생성 시각을 reflection 으로 박는다
    private fun version(
        id: Long,
        ready: Boolean,
        source: ItemSnapshotSource?,
        by: UUID,
        createdAt: LocalDateTime = LocalDateTime.now(),
        itemId: Long = 1L,
    ): ItemSnapshot {
        val snapshot =
            ItemSnapshot(
                itemId = itemId,
                name = "상품 $id",
                imageUrl = "https://cdn.example.com/$id.jpg",
                price = if (ready) 10_000 else null,
                currency = "KRW",
                extractedAt = createdAt,
                source = source,
                createdBy = by,
            )
        ReflectionTestUtils.setField(snapshot, "createdAt", createdAt)
        return snapshot.withId(id)
    }
}
