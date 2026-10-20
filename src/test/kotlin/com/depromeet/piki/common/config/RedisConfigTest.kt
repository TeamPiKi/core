package com.depromeet.piki.common.config

import io.lettuce.core.ClientOptions
import io.lettuce.core.resource.EpollProvider
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RedisConfigTest {
    private fun clientOptions(properties: DataRedisProperties = DataRedisProperties()): ClientOptions {
        val builder = ClientOptions.builder()
        RedisConfig().detectDeadConnections(properties).customize(builder)
        return builder.build()
    }

    @Test
    fun `연결 확인은 Redis 서버 기본 주기 300초보다 먼저 시작한다`() {
        val keepAlive = clientOptions().socketOptions.keepAlive

        assertTrue(keepAlive.isEnabled)
        assertTrue(keepAlive.idle < Duration.ofSeconds(300))
    }

    @Test
    fun `응답 없는 연결을 닫는 시간 한도가 켜진다`() {
        assertTrue(clientOptions().socketOptions.isEnableTcpUserTimeout)
    }

    @Test
    fun `설정한 connect-timeout 은 소켓 옵션을 바꿔도 남는다`() {
        val properties = DataRedisProperties().apply { connectTimeout = Duration.ofSeconds(1) }

        assertEquals(Duration.ofSeconds(1), clientOptions(properties).socketOptions.connectTimeout)
    }

    @Test
    fun `배포 서버 arm64 용 epoll 네이티브가 classpath 에 있다`() {
        assertNotNull(javaClass.classLoader.getResource("META-INF/native/libnetty_transport_native_epoll_aarch_64.so"))
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `리눅스에서 Lettuce 가 epoll 을 쓸 수 있다`() {
        assertTrue(EpollProvider.isAvailable())
    }
}
