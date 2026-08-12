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
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 카운터 산술의 경계 검증. 진입점 계약(429·503 응답 모양·차감 귀속)은 ItemQuotaIntegrationTest 가 맡고,
// 여기서는 "두 축이 한도 경계에서 정확히 어떻게 갈리고 창 TTL 이 어떻게 걸리는가" 만 본다.
//
// Redis 가 필요해 통합으로 두지만 DB 는 쓰지 않으므로 @Transactional 도 두지 않는다.
// 격리는 매 테스트의 새 UUID 키로 한다(Redis 는 트랜잭션 롤백 대상이 아니다). 전역 축 키도 운영 상수를 쓰지 않고
// 테스트마다 새로 만든다 — 운영 키를 건드리면 같은 Redis 를 쓰는 다른 테스트가 그 카운터에 걸려 깨진다.
class ItemQuotaStoreIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var store: RedisItemQuotaStore

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    @Test
    fun `잔액이 남아 있으면 허용하고 한도에 닿은 뒤부터 거부한다`() {
        val owner = newKey()
        val capacity = newKey()

        assertIs<ItemQuotaVerdict.Allowed>(consume(owner, capacity, amount = 7, ownerLimit = 10))
        // 누적 7 < 10 이라 아직 잔액이 있다. 이 요청이 정확히 한도를 채운다.
        assertIs<ItemQuotaVerdict.Allowed>(consume(owner, capacity, amount = 3, ownerLimit = 10))
        // 누적 10 >= 10 — 잔액이 0 이므로 이제부터 거부다.
        assertIs<ItemQuotaVerdict.OwnerExceeded>(consume(owner, capacity, amount = 1, ownerLimit = 10))
    }

    @Test
    fun `잔액보다 큰 요청도 통과시키고 누적이 한도를 넘어 음수 잔액이 된다`() {
        val owner = newKey()
        val capacity = newKey()
        consume(owner, capacity, amount = 8, ownerLimit = 10)

        // 남은 몫은 2 뿐이지만 요청량은 판정에 쓰지 않으므로 3 이 통째로 통과한다.
        // 사용자 입장에서 "마지막 한 번은 항상 성공" 이고, 넘긴 만큼은 다음 요청이 갚는다.
        assertIs<ItemQuotaVerdict.Allowed>(consume(owner, capacity, amount = 3, ownerLimit = 10))
        assertEquals("11", redisTemplate.opsForValue().get(owner))

        // 잔액이 음수(-1)라 다음 요청은 크기와 무관하게 거부된다.
        assertIs<ItemQuotaVerdict.OwnerExceeded>(consume(owner, capacity, amount = 1, ownerLimit = 10))
    }

    @Test
    fun `거부된 차감은 어느 카운터도 올리지 않는다`() {
        val owner = newKey()
        val capacity = newKey()
        consume(owner, capacity, amount = 10, ownerLimit = 10)

        // 넘치는 요청을 여러 번 반복해도 누적되지 않는다 — 누적하면 창이 끝나도 한도를 넘긴 채 시작해 사실상 영구 차단된다.
        repeat(3) {
            assertIs<ItemQuotaVerdict.OwnerExceeded>(consume(owner, capacity, amount = 5, ownerLimit = 10))
        }

        assertEquals("10", redisTemplate.opsForValue().get(owner))
        // 요청자 몫에서 거부된 요청이 전역 카운터를 올리면, 한 사용자의 남용이 서비스 전체 가용량을 갉아먹는다.
        assertEquals("10", redisTemplate.opsForValue().get(capacity))
    }

    @Test
    fun `요청자 몫이 남아 있어도 전역 가용량이 차면 거부한다`() {
        val owner = newKey()
        val capacity = newKey()
        // 전역만 소진시킨다. 이 사용자는 아직 한 건도 안 썼다.
        redisTemplate.opsForValue().set(capacity, "100", java.time.Duration.ofMinutes(1))

        val verdict = consume(owner, capacity, amount = 1, ownerLimit = 10, capacityLimit = 100)

        // "100명이 각자 10번" 을 막는 것이 이 축의 존재 이유다 — 개인 몫만 보면 전부 통과한다.
        assertIs<ItemQuotaVerdict.CapacityExceeded>(verdict)
        // 거부됐으므로 이 사용자의 몫은 손대지 않는다. 깎으면 안내대로 재시도할 때마다 자기 몫을 잃고,
        // 전역이 풀린 뒤에도 자기 한도에 걸려 429 를 받게 된다.
        assertNull(redisTemplate.opsForValue().get(owner))
    }

    @Test
    fun `두 축이 모두 소진이면 요청자 몫 소진을 사유로 준다`() {
        val owner = newKey()
        val capacity = newKey()
        redisTemplate.opsForValue().set(owner, "10", java.time.Duration.ofMinutes(1))
        redisTemplate.opsForValue().set(capacity, "100", java.time.Duration.ofMinutes(1))

        // 자기가 다 쓴 사용자에게 "서버가 바빠요"(503)를 주면 원인을 서버 탓으로 오해한다. 자기 몫이 먼저다.
        val verdict = consume(owner, capacity, amount = 1, ownerLimit = 10, capacityLimit = 100)

        assertIs<ItemQuotaVerdict.OwnerExceeded>(verdict)
    }

    @Test
    fun `허용된 차감은 두 카운터를 같은 양만큼 올린다`() {
        val owner = newKey()
        val capacity = newKey()

        val verdict = consume(owner, capacity, amount = 3, ownerLimit = 10)

        // 전역 누적값을 돌려주는 이유는 경고선 도달을 이 값으로 판정하기 때문이다.
        assertEquals(3L, assertIs<ItemQuotaVerdict.Allowed>(verdict).capacityUsed)
        assertEquals("3", redisTemplate.opsForValue().get(owner))
        assertEquals("3", redisTemplate.opsForValue().get(capacity))
    }

    @Test
    fun `첫 차감이 두 축의 창 TTL 을 걸고 이후 차감은 그 창을 연장하지 않는다`() {
        val owner = newKey()
        val capacity = newKey()

        consume(owner, capacity, amount = 1, ownerLimit = 10)
        val firstOwnerTtl = requireNotNull(redisTemplate.getExpire(owner, TimeUnit.MILLISECONDS))
        val firstCapacityTtl = requireNotNull(redisTemplate.getExpire(capacity, TimeUnit.MILLISECONDS))
        assertTrue(firstOwnerTtl in 1..WINDOW_MILLIS, "첫 차감이 요청자 축 창 TTL 을 걸어야 한다: $firstOwnerTtl")
        assertTrue(firstCapacityTtl in 1..WINDOW_MILLIS, "첫 차감이 전역 축 창 TTL 을 걸어야 한다: $firstCapacityTtl")

        consume(owner, capacity, amount = 1, ownerLimit = 10)
        // 고정 윈도우라 창은 첫 차감 시점부터 한 번만 흐른다. 차감마다 갱신하면 계속 쓰는 사용자의 창이 영영 안 끝난다.
        assertTrue(
            requireNotNull(redisTemplate.getExpire(owner, TimeUnit.MILLISECONDS)) <= firstOwnerTtl,
            "이후 차감이 요청자 축 창을 연장하면 안 된다",
        )
        assertTrue(
            requireNotNull(redisTemplate.getExpire(capacity, TimeUnit.MILLISECONDS)) <= firstCapacityTtl,
            "이후 차감이 전역 축 창을 연장하면 안 된다",
        )
    }

    @Test
    fun `거부 응답의 재시도 시간은 남은 창 안에서 최소 1초 이상이다`() {
        val owner = newKey()
        val capacity = newKey()
        // 잔액을 0 으로 만들어 다음 요청이 거부되게 한다.
        consume(owner, capacity, amount = 10, ownerLimit = 10)

        val verdict = consume(owner, capacity, amount = 1, ownerLimit = 10)

        val exceeded = assertIs<ItemQuotaVerdict.OwnerExceeded>(verdict)
        // 0 을 주면 클라가 즉시 재시도해 또 거부되므로 최소 1초를 보장한다. 상한은 창 길이다.
        assertTrue(
            exceeded.retryAfterSeconds in 1..(WINDOW_MILLIS / 1000),
            "재시도 시간이 1초 이상 창 이하여야 한다: ${exceeded.retryAfterSeconds}",
        )
    }

    @Test
    fun `전역 거부의 재시도 시간은 전역 창의 남은 시간을 따른다`() {
        val owner = newKey()
        val capacity = newKey()
        // 요청자 축은 길게, 전역 축은 짧게 둬서 어느 창을 보고 답하는지 가른다.
        consume(owner, capacity, amount = 1, ownerLimit = 10)
        redisTemplate.opsForValue().set(capacity, "100", java.time.Duration.ofSeconds(5))

        val verdict = consume(owner, capacity, amount = 1, ownerLimit = 10, capacityLimit = 100)

        val exceeded = assertIs<ItemQuotaVerdict.CapacityExceeded>(verdict)
        // 요청자 축 창(60초)이 아니라 전역 축 창(5초)을 봐야 한다. 남의 창 시간을 주면 회복 전에 재시도하거나
        // 회복된 뒤에도 기다리게 된다.
        assertTrue(exceeded.retryAfterSeconds in 1..5, "전역 창의 남은 시간이어야 한다: ${exceeded.retryAfterSeconds}")
    }

    @Test
    fun `차감량이 0 이하면 코드 버그로 즉시 실패한다`() {
        // 0 건 등록은 진입점 검증(이미지 개수 1~5)이 먼저 거르므로 여기 닿으면 호출부 버그다.
        assertFailsWith<IllegalArgumentException> {
            consume(newKey(), newKey(), amount = 0, ownerLimit = 10)
        }
    }

    private fun consume(
        ownerKey: String,
        capacityKey: String,
        amount: Int,
        ownerLimit: Int,
        capacityLimit: Int = SPACIOUS_CAPACITY,
    ): ItemQuotaVerdict =
        store.tryConsume(
            ownerKey = ownerKey,
            capacityKey = capacityKey,
            amount = amount,
            ownerLimit = ownerLimit,
            capacityLimit = capacityLimit,
            windowMillis = WINDOW_MILLIS,
        )

    // 매 테스트가 자기 키를 쓴다 — Redis 는 트랜잭션 롤백이 없으므로 격리를 키 이름으로 만든다.
    private fun newKey(): String = "quota:item:test:${UUID.randomUUID()}"

    companion object {
        private const val WINDOW_MILLIS = 60_000L

        // 요청자 축을 보는 테스트가 전역 축에 걸려 엉뚱한 사유로 실패하지 않도록 넉넉히 열어둔 값.
        private const val SPACIOUS_CAPACITY = 1_000_000
    }
}
