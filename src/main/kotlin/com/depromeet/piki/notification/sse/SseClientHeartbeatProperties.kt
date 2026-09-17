package com.depromeet.piki.notification.sse

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.convert.DurationUnit
import java.time.Duration
import java.time.temporal.ChronoUnit

// staleAfter 는 클라이언트 주기(30초)의 2배. 1.5배면 프록시 지연 한 번에 오탐 재연결이 나고, 그 FIN 이 #1029 경합의 재료다.
// 결측은 등록 시각부터 잰다. 하트비트 없는 구 버전 클라이언트는 staleAfter 마다 끊기고 재연결한다.
@ConfigurationProperties("notification.sse.client-heartbeat")
data class SseClientHeartbeatProperties(
    @field:DurationUnit(ChronoUnit.SECONDS)
    val staleAfter: Duration = Duration.ofSeconds(60),
)
