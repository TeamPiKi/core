package com.depromeet.piki.common.ratelimit

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ItemQuotaPropertiesTest {
    @Test
    fun `창 길이가 0 이면 부팅에서 실패한다`() {
        // 0 이면 첫 차감의 PEXPIRE 가 키를 즉시 지워 한도가 사실상 무제한이 된다 — 조용히 무력화되지 않게 부팅에서 막는다.
        assertFailsWith<IllegalArgumentException> { ItemQuotaProperties(window = Duration.ZERO) }
    }

    @Test
    fun `창 길이가 음수면 부팅에서 실패한다`() {
        assertFailsWith<IllegalArgumentException> { ItemQuotaProperties(window = Duration.ofSeconds(-1)) }
    }

    @Test
    fun `창 길이가 1ms 미만이면 양수여도 부팅에서 실패한다`() {
        // Redis PEXPIRE 가 ms 단위라 0.5ms 는 환산 결과가 0 이 되어 창이 즉시 만료된다. 그러면 매 요청이 새 창을
        // 열어 한도가 사실상 무제한이 되는데, 설정값만 보면 "양수니까 정상" 으로 보여 조용히 무력화된다.
        assertFailsWith<IllegalArgumentException> { ItemQuotaProperties(window = Duration.ofNanos(500_000)) }
        // 경계값 1ms 는 통과해야 한다.
        assertEquals(1, ItemQuotaProperties(window = Duration.ofMillis(1)).window.toMillis())
    }

    @Test
    fun `계정 한도가 0 이하면 부팅에서 실패한다`() {
        // 0 은 "무제한" 이 아니라 "전부 거부" 다. 오타로 등록 기능이 통째로 막히는 것을 부팅에서 드러낸다.
        assertFailsWith<IllegalArgumentException> { ItemQuotaProperties(userLimit = 0) }
        assertFailsWith<IllegalArgumentException> { ItemQuotaProperties(userLimit = -1) }
    }

    @Test
    fun `전역 상한이 0 이하면 부팅에서 실패한다`() {
        // 계정별과 달리 이 값이 0 이면 특정 사용자가 아니라 **모든** 사용자의 등록이 막힌다.
        assertFailsWith<IllegalArgumentException> { ItemQuotaProperties(capacityLimit = 0) }
        assertFailsWith<IllegalArgumentException> { ItemQuotaProperties(capacityLimit = -1) }
    }

    @Test
    fun `경고선 비율이 1 에서 100 밖이면 부팅에서 실패한다`() {
        // 0 이하면 첫 요청부터 경고가 울려 신호가 죽고, 100 초과면 영원히 안 울려 경고선이 없는 것과 같다.
        assertFailsWith<IllegalArgumentException> { ItemQuotaProperties(capacityAlertPercent = 0) }
        assertFailsWith<IllegalArgumentException> { ItemQuotaProperties(capacityAlertPercent = 101) }
        // 경계값 1·100 은 통과해야 한다 — 100 은 "상한에 닿을 때만 알린다" 는 유효한 설정이다.
        assertEquals(1, ItemQuotaProperties(capacityAlertPercent = 1).capacityAlertPercent)
        assertEquals(100, ItemQuotaProperties(capacityAlertPercent = 100).capacityAlertPercent)
    }

    @Test
    fun `경고선은 상한의 비율만큼으로 계산된다`() {
        // 운영 기본값과 같은 조합 — 3000 의 66% 는 1980 이다.
        val properties = ItemQuotaProperties(capacityLimit = 3_000, capacityAlertPercent = 66)

        assertEquals(1_980, properties.capacityAlertThreshold)
    }

    @Test
    fun `경고선 계산은 내림한다`() {
        // 정수 나눗셈이라 10 * 66 / 100 = 6.6 → 6. 경고가 한 건 앞당겨질 뿐이라 무해하다.
        assertEquals(6, ItemQuotaProperties(capacityLimit = 10, capacityAlertPercent = 66).capacityAlertThreshold)
    }

    @Test
    fun `경고선을 넘긴 첫 차감에서만 참이 된다`() {
        val properties = ItemQuotaProperties(capacityLimit = 3_000, capacityAlertPercent = 66)

        // 1979 까지는 아직 아래. 1980 을 만든 이 한 건이 경계를 넘긴 건이다.
        assertFalse(properties.crossedCapacityAlert(capacityUsed = 1_979, amount = 1))
        assertTrue(properties.crossedCapacityAlert(capacityUsed = 1_980, amount = 1))
        // 이미 넘긴 뒤의 차감은 거짓 — 참으로 두면 창이 끝날 때까지 매 요청이 같은 경고를 반복해 알림이 무뎌진다.
        assertFalse(properties.crossedCapacityAlert(capacityUsed = 1_981, amount = 1))
    }

    @Test
    fun `한 번에 경고선을 건너뛰어도 그 차감에서 참이 된다`() {
        val properties = ItemQuotaProperties(capacityLimit = 3_000, capacityAlertPercent = 66)

        // 이미지 5장 등록처럼 한 요청이 여러 건을 소모하면 경고선을 정확히 밟지 않고 넘어간다(1978 → 1983).
        // "누적 == 경고선" 으로 판정했다면 이 경우를 통째로 놓쳐 경고가 영영 안 울린다.
        assertTrue(properties.crossedCapacityAlert(capacityUsed = 1_983, amount = 5))
    }
}
