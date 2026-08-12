package com.depromeet.piki.common.ratelimit

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ItemQuotaPropertiesTest {
    @Test
    fun `축마다 다른 한도를 돌려준다`() {
        val properties = ItemQuotaProperties(wishLimit = 10, tournamentLimit = 30)

        assertEquals(10, properties.limitOf(ItemQuotaScope.WISH))
        assertEquals(30, properties.limitOf(ItemQuotaScope.TOURNAMENT))
    }

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
    fun `위시 한도가 0 이하면 부팅에서 실패한다`() {
        // 0 은 "무제한" 이 아니라 "전부 거부" 다. 오타로 등록 기능이 통째로 막히는 것을 부팅에서 드러낸다.
        assertFailsWith<IllegalArgumentException> { ItemQuotaProperties(wishLimit = 0) }
        assertFailsWith<IllegalArgumentException> { ItemQuotaProperties(wishLimit = -1) }
    }

    @Test
    fun `토너먼트 한도가 0 이하면 부팅에서 실패한다`() {
        assertFailsWith<IllegalArgumentException> { ItemQuotaProperties(tournamentLimit = 0) }
        assertFailsWith<IllegalArgumentException> { ItemQuotaProperties(tournamentLimit = -1) }
    }
}
