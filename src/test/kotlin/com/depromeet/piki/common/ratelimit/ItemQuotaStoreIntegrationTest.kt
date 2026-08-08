package com.depromeet.piki.common.ratelimit

import com.depromeet.piki.support.IntegrationTestSupport
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

// 카운터 산술의 경계 검증. 진입점 계약(429 응답 모양·차감 귀속)은 ItemQuotaIntegrationTest 가 맡고,
// 여기서는 "한도 경계에서 정확히 어떻게 갈리고 창 TTL 이 어떻게 걸리는가" 만 본다.
//
// Redis 가 필요해 통합으로 두지만 DB 는 쓰지 않으므로 @Transactional 도 두지 않는다.
// 격리는 매 테스트의 새 UUID 키로 한다(Redis 는 트랜잭션 롤백 대상이 아니다).
class ItemQuotaStoreIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var store: RedisItemQuotaStore

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    @Test
    fun `한도까지는 허용하고 한도를 넘기는 순간 거부한다`() {
        val key = newKey()

        // 경계 바로 아래·정확히 경계까지는 통과한다.
        assertIs<ItemQuotaVerdict.Allowed>(store.tryConsume(key, amount = 7, limit = 10, windowMillis = WINDOW_MILLIS))
        assertIs<ItemQuotaVerdict.Allowed>(store.tryConsume(key, amount = 3, limit = 10, windowMillis = WINDOW_MILLIS))
        // 10/10 을 쓴 상태에서 1 을 더하면 11 이라 거부다.
        assertIs<ItemQuotaVerdict.Exceeded>(store.tryConsume(key, amount = 1, limit = 10, windowMillis = WINDOW_MILLIS))
    }

    @Test
    fun `거부된 차감은 카운터를 올리지 않는다`() {
        val key = newKey()
        store.tryConsume(key, amount = 9, limit = 10, windowMillis = WINDOW_MILLIS)

        // 넘치는 요청을 여러 번 반복해도 누적되지 않는다 — 누적하면 창이 끝나도 한도를 넘긴 채 시작해 사실상 영구 차단된다.
        repeat(3) {
            val verdict = store.tryConsume(key, amount = 5, limit = 10, windowMillis = WINDOW_MILLIS)
            assertIs<ItemQuotaVerdict.Exceeded>(verdict)
        }

        assertEquals("9", redisTemplate.opsForValue().get(key))
        // 거부 뒤에도 남은 1 은 그대로 쓸 수 있다.
        assertIs<ItemQuotaVerdict.Allowed>(store.tryConsume(key, amount = 1, limit = 10, windowMillis = WINDOW_MILLIS))
    }

    @Test
    fun `all-or-nothing 이라 남은 몫보다 큰 요청은 부분 차감 없이 전부 거부된다`() {
        val key = newKey()
        store.tryConsume(key, amount = 8, limit = 10, windowMillis = WINDOW_MILLIS)

        // 남은 몫 2 에 3 을 요청 — 2 만 통과시키는 부분 성공은 등록 API 계약에 없다.
        assertIs<ItemQuotaVerdict.Exceeded>(store.tryConsume(key, amount = 3, limit = 10, windowMillis = WINDOW_MILLIS))

        assertEquals("8", redisTemplate.opsForValue().get(key))
    }

    @Test
    fun `첫 차감이 창 TTL 을 걸고 이후 차감은 그 창을 연장하지 않는다`() {
        val key = newKey()

        store.tryConsume(key, amount = 1, limit = 10, windowMillis = WINDOW_MILLIS)
        val firstTtl = requireNotNull(redisTemplate.getExpire(key, TimeUnit.MILLISECONDS))
        assertTrue(firstTtl in 1..WINDOW_MILLIS, "첫 차감이 창 TTL 을 걸어야 한다: $firstTtl")

        store.tryConsume(key, amount = 1, limit = 10, windowMillis = WINDOW_MILLIS)
        val secondTtl = requireNotNull(redisTemplate.getExpire(key, TimeUnit.MILLISECONDS))
        // 고정 윈도우라 창은 첫 차감 시점부터 한 번만 흐른다. 차감마다 갱신하면 계속 쓰는 사용자의 창이 영영 안 끝난다.
        assertTrue(secondTtl <= firstTtl, "이후 차감이 창을 연장하면 안 된다: first=$firstTtl second=$secondTtl")
    }

    @Test
    fun `거부 응답의 재시도 시간은 남은 창 안에서 최소 1초 이상이다`() {
        val key = newKey()
        store.tryConsume(key, amount = 10, limit = 10, windowMillis = WINDOW_MILLIS)

        val verdict = store.tryConsume(key, amount = 1, limit = 10, windowMillis = WINDOW_MILLIS)

        val exceeded = assertIs<ItemQuotaVerdict.Exceeded>(verdict)
        // 0 을 주면 클라가 즉시 재시도해 또 거부되므로 최소 1초를 보장한다. 상한은 창 길이다.
        assertTrue(
            exceeded.retryAfterSeconds in 1..(WINDOW_MILLIS / 1000),
            "재시도 시간이 1초 이상 창 이하여야 한다: ${exceeded.retryAfterSeconds}",
        )
    }

    @Test
    fun `차감량이 0 이하면 코드 버그로 즉시 실패한다`() {
        // 0 건 등록은 진입점 검증(이미지 개수 1~5)이 먼저 거르므로 여기 닿으면 호출부 버그다.
        assertFailsWith<IllegalArgumentException> {
            store.tryConsume(newKey(), amount = 0, limit = 10, windowMillis = WINDOW_MILLIS)
        }
    }

    // 매 테스트가 자기 키를 쓴다 — Redis 는 트랜잭션 롤백이 없으므로 격리를 키 이름으로 만든다.
    private fun newKey(): String = "quota:item:test:${UUID.randomUUID()}"

    companion object {
        private const val WINDOW_MILLIS = 60_000L
    }
}
