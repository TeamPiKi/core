package com.depromeet.piki.notification.sse

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

// 서버 -> 클라이언트 ping. 클라이언트 -> 서버 쪽은 "하트비트"(NotificationSseController.heartbeat).
// 30초는 nginx proxy_read_timeout(60s)과 모바일 NAT idle timeout 아래로 잡은 값이다.
// 멀티 인스턴스가 돼도 각 인스턴스가 자기 연결만 ping 하므로 중복 실행 방지가 필요 없다.
@Component
class SsePingScheduler(
    private val localDelivery: LocalSseDelivery,
    private val clientHeartbeat: SseClientHeartbeatProperties,
) {
    init {
        // 임계값이 ping 주기 이하면 매 tick 마다 전 연결이 결측으로 끊긴다. 단위 없는 숫자가 밀리초로 읽히는 실수도 여기서 걸린다.
        require(clientHeartbeat.staleAfter > Duration.ofMillis(PING_INTERVAL_MS)) {
            "notification.sse.client-heartbeat.stale-after 는 ping 주기(${PING_INTERVAL_MS}ms)보다 커야 한다 (현재=${clientHeartbeat.staleAfter})"
        }
    }

    // ping 이 먼저다. 결측 정리의 complete 가 느린 소켓에 막히면 전 연결의 keep-alive 예산(60s - 30s)을 갉아먹는다.
    @Scheduled(fixedRate = PING_INTERVAL_MS)
    fun tick() {
        localDelivery.ping()
        localDelivery.evictStale(Instant.now(), clientHeartbeat.staleAfter)
    }

    companion object {
        const val PING_INTERVAL_MS = 30_000L
    }
}
