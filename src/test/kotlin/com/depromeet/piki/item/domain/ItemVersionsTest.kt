package com.depromeet.piki.item.domain

import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// 카드 표시값 규칙(#1051)의 분기 망라. 입력은 한 상품의 버전들, 카드 주인, 카드가 기다리는 행뿐이라 프레임워크 없이 검증한다.
// 불변식: 남의 진행 중·남의 수기·남의 미완은 내 카드에 새어 들어오지 않는다. 내 수기값은 그보다 새로운 서버 READY 에만 진다.
// 새로고침은 어떤 플로우도 막지 않는다 — 진행 중은 그 행을 기다리는 카드에만 보인다.
class ItemVersionsTest {
    private val me = UUID.randomUUID()
    private val other = UUID.randomUUID()

    @Test
    fun `카드가 기다리는 행이 진행 중이면 완성 값이 있어도 진행 중을 보인다 - 내가 시작한 갱신`() {
        val ready = machineReady(1, by = other)
        val myPending = pending(2, by = me)

        assertEquals(myPending, display(listOf(ready, myPending), waitingOn = myPending))
    }

    @Test
    fun `같은 사람의 다른 카드는 그 갱신을 기다리지 않으므로 흔들리지 않는다 - 위시 새로고침 중인 사람의 토너먼트 카드`() {
        // 토너먼트 카드는 출전 pin(READY)을 기다린다. 위시 쪽 새로고침이 만든 진행 중 행은 만든 사람이 나라도 이 카드와 무관하다.
        val pinned = machineReady(1, by = me)
        val myWishRefresh = pending(2, by = me)

        assertEquals(pinned, display(listOf(pinned, myWishRefresh), waitingOn = pinned))
    }

    @Test
    fun `남이 시킨 진행 중 버전은 내 카드에 보이지 않는다 - 내 카드는 완성 값을 그대로 본다`() {
        val ready = machineReady(1, by = me)
        val othersPending = pending(2, by = other)

        assertEquals(ready, display(listOf(ready, othersPending), waitingOn = ready))
    }

    @Test
    fun `합류한 진행 중은 만든 사람이 남이라도 보인다 - 새로고침 합류`() {
        // B 가 새로고침을 누르는데 A 의 새로고침이 진행 중이면 B 의 카드가 그 행을 기다리게 된다(#826).
        val ready = machineReady(1, by = me)
        val othersPending = pending(2, by = other)

        assertEquals(othersPending, display(listOf(ready, othersPending), waitingOn = othersPending))
    }

    @Test
    fun `내 수기값은 최신 서버 READY 보다 새로우면 이긴다`() {
        val ready = machineReady(1, by = other)
        val myManual = manual(2, by = me)

        assertEquals(myManual, display(listOf(ready, myManual), waitingOn = myManual))
    }

    @Test
    fun `내 수기값보다 새로운 서버 READY 가 생기면 서버값으로 돌아간다 - 누가 새로고침했든`() {
        val myManual = manual(1, by = me)
        val newerReady = machineReady(2, by = other)

        assertEquals(newerReady, display(listOf(myManual, newerReady), waitingOn = myManual))
    }

    @Test
    fun `남의 수기값은 내 카드에 보이지 않는다`() {
        val ready = machineReady(1, by = other)
        val othersManual = manual(2, by = other)

        assertEquals(ready, display(listOf(ready, othersManual), waitingOn = ready))
    }

    @Test
    fun `새로고침이 실패해도 카드는 새로고침 전과 같다 - FAILED 는 후보가 아니다`() {
        val ready = machineReady(1, by = other)
        val myManual = manual(2, by = me)
        val myFailed = failed(3, by = me)

        assertEquals(myManual, display(listOf(ready, myManual, myFailed), waitingOn = myFailed))
    }

    @Test
    fun `수기값만 있는 상품의 새로고침이 실패해도 내 수기값을 본다 - 빈 카드가 되지 않는다`() {
        val myManual = manual(1, by = me)
        val myFailed = failed(2, by = me)

        assertEquals(myManual, display(listOf(myManual, myFailed), waitingOn = myFailed))
    }

    @Test
    fun `내 새로고침이 INCOMPLETE 로 끝나면 옛 READY 대신 일부만 빈 상태를 보인다 - 수기를 기다린다`() {
        val ready = machineReady(1, by = me)
        val myIncomplete = incomplete(2, by = me)

        assertEquals(myIncomplete, display(listOf(ready, myIncomplete), waitingOn = myIncomplete))
    }

    @Test
    fun `남의 새로고침이 INCOMPLETE 로 끝나도 내 카드는 READY 를 그대로 본다`() {
        val ready = machineReady(1, by = me)
        val othersIncomplete = incomplete(2, by = other)

        assertEquals(ready, display(listOf(ready, othersIncomplete), waitingOn = ready))
    }

    @Test
    fun `내 INCOMPLETE 보다 새로운 서버 READY 가 생기면 서버값을 본다`() {
        val myIncomplete = incomplete(1, by = me)
        val newerReady = machineReady(2, by = other)

        assertEquals(newerReady, display(listOf(myIncomplete, newerReady), waitingOn = myIncomplete))
    }

    @Test
    fun `값이 하나도 없으면 기다리던 행의 결과를 본다 - 등록 합류가 INCOMPLETE 로 끝난 경우`() {
        // B 가 A 의 첫 파싱에 합류했고 그 파싱이 INCOMPLETE 로 끝났다. B 도 값이 없으니 그 결과가 B 의 등록 결과다.
        val joinedIncomplete = incomplete(1, by = other)

        assertEquals(joinedIncomplete, display(listOf(joinedIncomplete), waitingOn = joinedIncomplete))
    }

    @Test
    fun `남이 뒤에 남긴 실패는 내가 기다리던 결과를 지우지 않는다`() {
        val joinedIncomplete = incomplete(1, by = other)
        val othersLaterFailed = failed(2, by = other)

        val versions = listOf(joinedIncomplete, othersLaterFailed)

        assertEquals(joinedIncomplete, display(versions, waitingOn = joinedIncomplete))
    }

    @Test
    fun `값이 하나도 없어도 남의 수기값은 보지 않는다 - 내 실패를 본다`() {
        val myFailed = failed(1, by = me)
        val othersManual = manual(2, by = other)

        assertEquals(myFailed, display(listOf(myFailed, othersManual), waitingOn = myFailed))
    }

    @Test
    fun `출처를 모르는 도입 전 READY 는 만든 사람이 채워져 있어도 공유 값이다`() {
        // 추정 백필이 옛 행에 만든 사람을 채우므로, 공유 여부는 만든 사람이 아니라 출처(수기 아님)로만 가른다.
        val legacy = version(1, ItemStatus.READY, source = null, by = other)

        assertEquals(legacy, display(listOf(legacy), waitingOn = legacy))
    }

    // ── 해소 통지 판정(recovers): 이 버전으로 카드가 채워지는가 ──────────────────────────

    @Test
    fun `기다리던 행이 FAILED 였고 남의 서버 READY 가 생기면 채워진다`() {
        val myFailed = failed(1, by = me)
        val othersReady = machineReady(2, by = other)

        assertTrue(ItemVersions.of(listOf(myFailed, othersReady)).recovers(me, waitingOn = 1, snapshotId = 2))
    }

    @Test
    fun `기다리던 행이 INCOMPLETE 여도 남의 서버 READY 가 생기면 채워진다`() {
        val myIncomplete = incomplete(1, by = me)
        val othersReady = machineReady(2, by = other)

        assertTrue(ItemVersions.of(listOf(myIncomplete, othersReady)).recovers(me, waitingOn = 1, snapshotId = 2))
    }

    @Test
    fun `그 버전을 기다리는 카드는 채워진 것이 아니라 완료다 - 두 알림은 배타적`() {
        val ready = machineReady(1, by = me)

        assertFalse(ItemVersions.of(listOf(ready)).recovers(me, waitingOn = 1, snapshotId = 1))
    }

    @Test
    fun `기다리던 행이 FAILED 라도 내 수기값이 이미 카드에 떠 있었으면 채워진 것이 아니다`() {
        // 옛 규칙(기다리는 행의 상태만 봄)은 이 카드에 해소 통지를 보냈다. 표시값 규칙으로는 카드가 비어 있던 적이 없다.
        val myManual = manual(1, by = me)
        val myFailed = failed(2, by = me)
        val othersReady = machineReady(3, by = other)

        val versions = ItemVersions.of(listOf(myManual, myFailed, othersReady))

        assertFalse(versions.recovers(me, waitingOn = 2, snapshotId = 3))
    }

    @Test
    fun `옛 READY 를 보던 카드는 남의 새 READY 로 바뀌어도 채워진 것이 아니다 - 이미 값을 보고 있었다`() {
        val oldReady = machineReady(1, by = me)
        val newReady = machineReady(2, by = other)

        assertFalse(ItemVersions.of(listOf(oldReady, newReady)).recovers(me, waitingOn = 1, snapshotId = 2))
    }

    @Test
    fun `다른 파싱을 기다리는 진행 중 카드는 채워진 것이 아니다 - 자기 결과를 따로 받는다`() {
        val myPending = pending(1, by = me)
        val othersReady = machineReady(2, by = other)

        assertFalse(ItemVersions.of(listOf(myPending, othersReady)).recovers(me, waitingOn = 1, snapshotId = 2))
    }

    @Test
    fun `성공 버전이 둘 쌓여도 채워진 순간의 버전 한 번만 채워진 것으로 본다 - 뒤 버전이 앞 버전을 가리지 않는다`() {
        // 비동기 디스패치·연속 새로고침으로 P1·P2 가 다 있는 채 P1 이벤트를 처리해도 P1 이 채운 것이고, P2 는 이미 값을 보던 카드다.
        val myFailed = failed(1, by = me)
        val p1 = machineReady(2, by = other)
        val p2 = machineReady(3, by = other)
        val versions = ItemVersions.of(listOf(myFailed, p1, p2))

        assertTrue(versions.recovers(me, waitingOn = 1, snapshotId = 2))
        assertFalse(versions.recovers(me, waitingOn = 1, snapshotId = 3))
    }

    @Test
    fun `그 버전 뒤에 기다리기 시작한 카드는 채워진 것이 아니다`() {
        val othersReady = machineReady(1, by = other)
        val myLaterFailed = failed(2, by = me)

        assertFalse(ItemVersions.of(listOf(othersReady, myLaterFailed)).recovers(me, waitingOn = 2, snapshotId = 1))
    }

    @Test
    fun `판정 대상 버전이 이 상품에 없으면 코드 버그다`() {
        val ready = machineReady(1, by = me)

        assertFailsWith<IllegalArgumentException> {
            ItemVersions.of(listOf(ready)).recovers(me, waitingOn = 1, snapshotId = 99)
        }
    }

    @Test
    fun `버전이 없으면 카드가 될 수 없다`() {
        assertFailsWith<IllegalArgumentException> { ItemVersions.of(emptyList()) }
    }

    private fun display(
        versions: List<ItemSnapshot>,
        waitingOn: ItemSnapshot,
    ): ItemSnapshot = ItemVersions.of(versions).displayFor(viewer = me, waitingOn = waitingOn.getId())

    // ── fixture ─────────────────────────────────────────────────────────────
    // 영속화 없이 id 를 흉내 내야 "최신" 판정(id 오름차순)을 검증할 수 있어 reflection 으로 id 를 박는다.

    private fun machineReady(
        id: Long,
        by: UUID?,
    ): ItemSnapshot = version(id, ItemStatus.READY, ItemSnapshotSource.SERVER, by)

    private fun manual(
        id: Long,
        by: UUID,
    ): ItemSnapshot = version(id, ItemStatus.READY, ItemSnapshotSource.MANUAL, by)

    private fun incomplete(
        id: Long,
        by: UUID,
    ): ItemSnapshot = version(id, ItemStatus.INCOMPLETE, ItemSnapshotSource.SERVER, by)

    private fun failed(
        id: Long,
        by: UUID,
    ): ItemSnapshot = version(id, ItemStatus.FAILED, ItemSnapshotSource.SERVER, by)

    private fun pending(
        id: Long,
        by: UUID,
    ): ItemSnapshot = version(id, ItemStatus.PENDING, null, by)

    private fun version(
        id: Long,
        status: ItemStatus,
        source: ItemSnapshotSource?,
        by: UUID?,
    ): ItemSnapshot {
        val hasValue = status == ItemStatus.READY || status == ItemStatus.INCOMPLETE
        val snapshot =
            ItemSnapshot(
                itemId = 1L,
                name = if (hasValue) "상품 $id" else null,
                price = if (status == ItemStatus.READY) 10_000 else null,
                imageUrl = if (status == ItemStatus.READY) "https://cdn.example.com/$id.jpg" else null,
                status = status,
                extractedAt = if (hasValue) LocalDateTime.now() else null,
                source = source,
                createdBy = by,
            )
        ItemSnapshot::class.java.superclass
            .getDeclaredField("id")
            .apply { isAccessible = true }
            .set(snapshot, id)
        return snapshot
    }
}
