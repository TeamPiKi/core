package com.depromeet.piki.support

import com.depromeet.piki.item.service.ItemParsingScheduler
import org.awaitility.Awaitility.await
import java.time.Duration

private val TICK_INTERVAL = Duration.ofMillis(50)

// 테스트 컨텍스트는 @Scheduled 를 끄므로(IntegrationTestSupport) PENDING 을 집어 줄 배경 폴링이 없다.
fun ItemParsingScheduler.awaitTicking(
    timeout: Duration = Duration.ofSeconds(5),
    condition: () -> Boolean,
) {
    await().atMost(timeout).pollInterval(TICK_INTERVAL).until {
        dispatch()
        condition()
    }
}
