package com.depromeet.piki.common.config

import io.lettuce.core.SocketOptions
import io.lettuce.core.SocketOptions.KeepAliveOptions
import io.lettuce.core.SocketOptions.TcpUserTimeoutOptions
import io.lettuce.core.resource.EpollProvider
import io.lettuce.core.resource.IOUringProvider
import org.slf4j.LoggerFactory
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

// 끊긴 줄 모르는 연결에 명령을 보내면 OS 재전송 한도(약 15분) 동안 모든 Redis 호출이 시간 초과로 실패한다(#1234).
@Configuration
class RedisConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun detectDeadConnections(properties: DataRedisProperties): LettuceClientOptionsBuilderCustomizer =
        LettuceClientOptionsBuilderCustomizer { builder ->
            builder.socketOptions(socketOptions(properties, EpollProvider.isAvailable() || IOUringProvider.isAvailable()))
        }

    internal fun socketOptions(
        properties: DataRedisProperties,
        nativeTransportAvailable: Boolean,
    ): SocketOptions {
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
                )
        // Lettuce 7 은 io_uring·epoll 없이 이 값을 걸면 연결 자체를 거부한다. 로컬 macOS 는 네이티브가 없어 빼고 연결한다.
        if (nativeTransportAvailable) {
            socketOptions.tcpUserTimeout(TcpUserTimeoutOptions.builder().enable().tcpUserTimeout(TCP_USER_TIMEOUT).build())
        } else {
            log.warn("Redis 네이티브 전송(io_uring·epoll) 없음. TCP_USER_TIMEOUT 없이 연결")
        }
        // socketOptions 를 통째로 바꾸므로 Boot 가 먼저 넣은 connect-timeout 을 다시 싣는다.
        properties.connectTimeout?.let { socketOptions.connectTimeout(it) }
        return socketOptions.build()
    }

    companion object {
        // Redis 서버 기본 tcp-keepalive(300초)보다 먼저 확인해야, 앱 서버의 NAT 기록이 지워져도 서버가 연결을 닫기 전에 되살아난다.
        val KEEPALIVE_IDLE: Duration = Duration.ofSeconds(30)
        val KEEPALIVE_INTERVAL: Duration = Duration.ofSeconds(10)
        const val KEEPALIVE_COUNT = 3

        val TCP_USER_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
