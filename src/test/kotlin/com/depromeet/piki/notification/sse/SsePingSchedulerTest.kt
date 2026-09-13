package com.depromeet.piki.notification.sse

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SsePingSchedulerTest {
    private val localDelivery = LocalSseDelivery(SseEmitterRegistry())

    @Test
    fun `기본 프로퍼티(임계값 60초)로는 생성된다`() {
        val properties = SseClientHeartbeatProperties()

        SsePingScheduler(localDelivery, properties)

        assertEquals(Duration.ofSeconds(60), properties.staleAfter)
    }

    @Test
    fun `임계값이 ping 주기 이하면 생성이 실패한다`() {
        assertFailsWith<IllegalArgumentException> {
            SsePingScheduler(localDelivery, SseClientHeartbeatProperties(staleAfter = Duration.ofMillis(SsePingScheduler.PING_INTERVAL_MS)))
        }
        assertFailsWith<IllegalArgumentException> {
            SsePingScheduler(localDelivery, SseClientHeartbeatProperties(staleAfter = Duration.ofMillis(60)))
        }
    }
}
