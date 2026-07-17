package com.depromeet.piki.notification.sse

import com.depromeet.piki.support.IntegrationTestSupport
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// RedisSseEventLog 를 실제 Redis(Testcontainers)로 검증한다 — XADD id 발급·exclusive 조회·상한 trim·TTL 은
// Redis 실 거동이 계약이라 stub 으로 대신할 수 없다(RedisRefreshTokenStoreIntegrationTest 와 같은 결).
// 통합 테스트 일반의 적재·replay 흐름은 StubSseEventLog(@Primary)가 담당하고, 여기는 concrete 빈을 직접 주입한다.
// 스트림 키는 유저별 + 랜덤 userId 라 테스트 간 간섭이 없고, TTL 이 있어 명시 정리도 불필요하다.
class RedisSseEventLogIntegrationTest : IntegrationTestSupport() {
    @Autowired private lateinit var redisSseEventLog: RedisSseEventLog

    @Autowired private lateinit var redisTemplate: StringRedisTemplate

    @Test
    fun `append 는 단조 증가 id 를 발급하고 readAfter 는 그 초과분만 오래된 것부터 원본 그대로 돌려준다`() {
        val userId = UUID.randomUUID()
        val first = requireNotNull(redisSseEventLog.append(userId, "notification", """{"id":1}"""))
        val second =
            requireNotNull(redisSseEventLog.append(userId, "silent-sync", """{"type":"UNREAD_COUNT_CHANGED"}"""))
        val third = requireNotNull(redisSseEventLog.append(userId, "notification", """{"id":3}"""))

        // exclusive — 기준점 자신은 다시 오지 않고, 발생 순서(오래된 것부터)가 유지된다.
        val missed = redisSseEventLog.readAfter(userId, first, 10)
        assertEquals(listOf(second, third), missed.map { it.id })
        assertEquals(listOf("silent-sync", "notification"), missed.map { it.eventName })
        // payload 는 적재한 바이트 그대로다 — replay 가 live 와 같은 와이어를 재현하는 근거.
        assertEquals("""{"type":"UNREAD_COUNT_CHANGED"}""", missed.first().payloadJson)
    }

    @Test
    fun `가장 오래된 항목보다 이전 id 로 조회하면 전체가 돌아온다`() {
        val userId = UUID.randomUUID()
        redisSseEventLog.append(userId, "notification", """{"id":1}""")
        redisSseEventLog.append(userId, "notification", """{"id":2}""")

        assertEquals(2, redisSseEventLog.readAfter(userId, "0-0", 10).size)
    }

    @Test
    fun `readAfter 는 limit 건까지만 오래된 것부터 돌려준다`() {
        val userId = UUID.randomUUID()
        val ids = (1..5).map { requireNotNull(redisSseEventLog.append(userId, "notification", """{"id":$it}""")) }

        assertEquals(ids.take(3), redisSseEventLog.readAfter(userId, "0-0", 3).map { it.id })
    }

    @Test
    fun `보관 상한을 넘으면 오래된 항목부터 잘린다`() {
        val userId = UUID.randomUUID()
        val overflow = 5
        val firstId =
            requireNotNull(redisSseEventLog.append(userId, "notification", """{"id":0}"""))
        repeat(RedisSseEventLog.MAX_LEN.toInt() + overflow - 1) { i ->
            redisSseEventLog.append(userId, "notification", """{"id":${i + 1}}""")
        }

        val all = redisSseEventLog.readAfter(userId, "0-0", RedisSseEventLog.MAX_LEN.toInt() + overflow)
        // 정확 trim — 잔존 건수가 정확히 MAX_LEN 이라 replay 의 "trim 구멍 배제" 판정(상한 초과 = 통째 생략)이 성립한다.
        assertEquals(RedisSseEventLog.MAX_LEN.toInt(), all.size)
        assertTrue(all.none { it.id == firstId })
    }

    @Test
    fun `적재는 스트림 키에 보존 TTL 을 건다`() {
        val userId = UUID.randomUUID()
        redisSseEventLog.append(userId, "notification", """{"id":1}""")

        val ttlSeconds = redisTemplate.getExpire("sse:events:$userId", TimeUnit.SECONDS)
        assertTrue(ttlSeconds in 1..RedisSseEventLog.TTL.seconds)
    }

    @Test
    fun `보관 상한은 replay 상한보다 커서 trim 이 일어난 공백이 상한 초과 생략으로 귀결된다`() {
        // trim 으로 lastEventId 이후가 잘렸다면 잔존 전부(=MAX_LEN 건)가 초과분으로 조회되므로,
        // MAX_LEN > REPLAY_LIMIT 이면 replay 가 반드시 "상한 초과 통째 생략" 분기로 빠진다. 이 관계가 깨지면
        // 구멍 난 구간을 연속인 척 replay 하게 되므로 상수 관계를 테스트로 고정한다.
        assertTrue(RedisSseEventLog.MAX_LEN > SseReconnectReplayer.REPLAY_LIMIT)
    }
}
