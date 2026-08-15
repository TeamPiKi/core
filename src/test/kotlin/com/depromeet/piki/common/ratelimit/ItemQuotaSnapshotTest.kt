package com.depromeet.piki.common.ratelimit

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ItemQuotaSnapshotTest {
    @Test
    fun `오버라이드가 없으면 env 기본값이 그대로 실효값이 된다`() {
        val properties = ItemQuotaProperties(userLimit = 30, capacityLimit = 3_000, capacityAlertPercent = 66)

        val snapshot = ItemQuotaSnapshot.of(properties)

        assertEquals(30, snapshot.userLimit)
        assertEquals(3_000, snapshot.capacityLimit)
        assertEquals(66, snapshot.capacityAlertPercent)
    }

    @Test
    fun `오버라이드에 값이 있는 노브만 덮고 나머지는 기본값이 남는다`() {
        // 부분 오버라이드가 이 설계의 핵심이다 — 상한 하나만 급히 내릴 때 나머지를 화면에서 다시 적지 않아도 된다.
        val properties = ItemQuotaProperties(userLimit = 30, capacityLimit = 3_000, capacityAlertPercent = 66)
        val override = ItemQuotaSettingsEntity(capacityLimit = 500)

        val snapshot = ItemQuotaSnapshot.of(properties, override)

        assertEquals(500, snapshot.capacityLimit)
        assertEquals(30, snapshot.userLimit)
        assertEquals(66, snapshot.capacityAlertPercent)
    }

    @Test
    fun `사용 여부는 false 로도 덮인다`() {
        // Boolean 오버라이드를 Elvis 로 풀 때 false 를 "값 없음" 으로 흘려보내는 실수가 흔하다.
        // 그러면 "한도를 끈다" 는 조작이 조용히 무시돼, 정상 사용자를 막고 있는 상태를 되돌릴 수 없다.
        val properties = ItemQuotaProperties(enabled = true)

        val snapshot = ItemQuotaSnapshot.of(properties, ItemQuotaSettingsEntity(enabled = false))

        assertFalse(snapshot.enabled)
    }

    @Test
    fun `창 길이는 오버라이드 대상이 아니라 항상 env 값을 쓴다`() {
        val properties = ItemQuotaProperties(window = Duration.ofMinutes(30))

        val snapshot = ItemQuotaSnapshot.of(properties, ItemQuotaSettingsEntity(userLimit = 5))

        assertEquals(Duration.ofMinutes(30), snapshot.window)
    }

    @Test
    fun `경고선은 상한의 비율만큼으로 계산된다`() {
        // 운영 기본값과 같은 조합 — 3000 의 66% 는 1980 이다.
        val snapshot = snapshotOf(capacityLimit = 3_000, capacityAlertPercent = 66)

        assertEquals(1_980, snapshot.capacityAlertThreshold)
    }

    @Test
    fun `경고선 계산은 내림한다`() {
        // 정수 나눗셈이라 10 * 66 / 100 = 6.6 → 6. 경고가 한 건 앞당겨질 뿐이라 무해하다.
        assertEquals(6, snapshotOf(capacityLimit = 10, capacityAlertPercent = 66).capacityAlertThreshold)
    }

    @Test
    fun `경고선을 넘긴 첫 차감에서만 참이 된다`() {
        val snapshot = snapshotOf(capacityLimit = 3_000, capacityAlertPercent = 66)

        // 1979 까지는 아직 아래. 1980 을 만든 이 한 건이 경계를 넘긴 건이다.
        assertFalse(snapshot.crossedCapacityAlert(capacityUsed = 1_979, amount = 1))
        assertTrue(snapshot.crossedCapacityAlert(capacityUsed = 1_980, amount = 1))
        // 이미 넘긴 뒤의 차감은 거짓 — 참으로 두면 창이 끝날 때까지 매 요청이 같은 경고를 반복해 알림이 무뎌진다.
        assertFalse(snapshot.crossedCapacityAlert(capacityUsed = 1_981, amount = 1))
    }

    @Test
    fun `한 번에 경고선을 건너뛰어도 그 차감에서 참이 된다`() {
        val snapshot = snapshotOf(capacityLimit = 3_000, capacityAlertPercent = 66)

        // 이미지 5장 등록처럼 한 요청이 여러 건을 소모하면 경고선을 정확히 밟지 않고 넘어간다(1978 → 1983).
        // "누적 == 경고선" 으로 판정했다면 이 경우를 통째로 놓쳐 경고가 영영 안 울린다.
        assertTrue(snapshot.crossedCapacityAlert(capacityUsed = 1_983, amount = 5))
    }

    @Test
    fun `범위를 벗어난 값으로는 만들 수 없다`() {
        // 불변식 층이다 — env 는 부팅에서, 백오피스 입력은 admin 경계에서 먼저 걸러진다.
        // 여기 닿았다면 어느 경계가 검증을 빠뜨린 것이라 코드 버그다.
        assertFailsWith<IllegalArgumentException> { snapshotOf(userLimit = 0) }
        assertFailsWith<IllegalArgumentException> { snapshotOf(capacityLimit = 0) }
        assertFailsWith<IllegalArgumentException> { snapshotOf(capacityAlertPercent = 0) }
        assertFailsWith<IllegalArgumentException> { snapshotOf(capacityAlertPercent = 101) }
        assertFailsWith<IllegalArgumentException> { snapshotOf(window = Duration.ZERO) }
    }

    private fun snapshotOf(
        enabled: Boolean = true,
        window: Duration = Duration.ofHours(1),
        userLimit: Int = 30,
        capacityLimit: Int = 3_000,
        capacityAlertPercent: Int = 66,
    ) = ItemQuotaSnapshot(enabled, window, userLimit, capacityLimit, capacityAlertPercent)
}
