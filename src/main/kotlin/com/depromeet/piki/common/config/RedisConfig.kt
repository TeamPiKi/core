package com.depromeet.piki.common.config

import io.lettuce.core.SocketOptions
import io.lettuce.core.SocketOptions.KeepAliveOptions
import io.lettuce.core.SocketOptions.TcpUserTimeoutOptions
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

// 끊긴 줄 모르는 연결에 명령을 보내면 OS 재전송 한도(약 15분) 동안 모든 Redis 호출이 시간 초과로 실패한다(#1234).
@Configuration
class RedisConfig {
    @Bean
    fun detectDeadConnections(properties: DataRedisProperties): LettuceClientOptionsBuilderCustomizer =
        LettuceClientOptionsBuilderCustomizer { builder ->
            val socketOptions =
                SocketOptions
                    .builder()
                    .keepAlive(
                        KeepAliveOptions
                            .builder()
                            .enable()
                            .idle(KEEPALIVE_IDLE)
                            .interval(KEEPALIVE_INTERVAL)
                            .count(KEEPALIVE_COUNT)
                            .build(),
                    ).tcpUserTimeout(
                        TcpUserTimeoutOptions
                            .builder()
                            .enable()
                            .tcpUserTimeout(TCP_USER_TIMEOUT)
                            .build(),
                    )
            // socketOptions 를 통째로 바꾸므로 Boot 가 먼저 넣은 connect-timeout 을 다시 싣는다.
            properties.connectTimeout?.let { socketOptions.connectTimeout(it) }
            builder.socketOptions(socketOptions.build())
        }

    companion object {
        // Redis 서버 기본 tcp-keepalive(300초)보다 먼저 확인해야, 앱 서버의 NAT 기록이 지워져도 서버가 연결을 닫기 전에 되살아난다.
        val KEEPALIVE_IDLE: Duration = Duration.ofSeconds(30)
        val KEEPALIVE_INTERVAL: Duration = Duration.ofSeconds(10)
        const val KEEPALIVE_COUNT = 3

        // Lettuce 는 epoll 에서만 이 값을 건다. 런타임에 epoll 네이티브가 없으면 경고 로그만 남기고 빠진다.
        val TCP_USER_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
