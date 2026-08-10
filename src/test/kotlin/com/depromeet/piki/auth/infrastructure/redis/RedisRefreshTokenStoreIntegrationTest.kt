package com.depromeet.piki.auth.infrastructure.redis

import com.depromeet.piki.support.IntegrationTestSupport
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RedisRefreshTokenStoreIntegrationTest : IntegrationTestSupport() {
    @Autowired
    private lateinit var store: RedisRefreshTokenStore

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    private fun sid() = UUID.randomUUID().toString()

    @Test
    fun `save 한 refresh token 을 get 으로 조회할 수 있다`() {
        val userId = UUID.randomUUID()
        val sessionId = sid()
        val token = "refresh-token-for-$userId"

        store.save(userId, sessionId, token)

        assertEquals(token, store.get(userId, sessionId))

        store.delete(userId, sessionId)
    }

    @Test
    fun `delete 후 get 은 null 을 반환한다`() {
        val userId = UUID.randomUUID()
        val sessionId = sid()
        store.save(userId, sessionId, "some-token")

        store.delete(userId, sessionId)

        assertNull(store.get(userId, sessionId))
    }

    @Test
    fun `같은 세션으로 두 번 save 하면 두번째 값이 첫번째를 덮어쓴다`() {
        val userId = UUID.randomUUID()
        val sessionId = sid()
        store.save(userId, sessionId, "first")
        store.save(userId, sessionId, "second")

        assertEquals("second", store.get(userId, sessionId))

        store.delete(userId, sessionId)
    }

    @Test
    fun `존재하지 않는 세션으로 get 하면 null 을 반환한다`() {
        assertNull(store.get(UUID.randomUUID(), sid()))
    }

    @Test
    fun `key 는 refresh 콜론 userId 콜론 sessionId 패턴이다`() {
        val userId = UUID.randomUUID()
        val sessionId = sid()
        store.save(userId, sessionId, "token")

        assertNotNull(redisTemplate.opsForValue().get("refresh:$userId:$sessionId"))

        store.delete(userId, sessionId)
    }

    // ── 다중 기기(#893) ────────────────────────────────────────────────
    // 이 묶음이 이슈의 본체다. 이전에는 키가 userId 하나라 아래 세 가지가 전부 깨졌다.

    @Test
    fun `다른 세션으로 save 해도 서로 덮어쓰지 않는다`() {
        val userId = UUID.randomUUID()
        val phone = sid()
        val laptop = sid()

        store.save(userId, phone, "phone-token")
        store.save(userId, laptop, "laptop-token")

        assertEquals("phone-token", store.get(userId, phone))
        assertEquals("laptop-token", store.get(userId, laptop))

        store.deleteAll(userId)
    }

    @Test
    fun `한 세션을 delete 해도 다른 세션은 살아 있다`() {
        val userId = UUID.randomUUID()
        val phone = sid()
        val laptop = sid()
        store.save(userId, phone, "phone-token")
        store.save(userId, laptop, "laptop-token")

        store.delete(userId, phone)

        assertNull(store.get(userId, phone))
        assertEquals("laptop-token", store.get(userId, laptop))

        store.deleteAll(userId)
    }

    // 이슈가 보고한 실패 모드 그대로다 — 옛 기기의 뒤늦은 갱신이 재사용으로 잡혀도
    // 방금 로그인한 기기는 멀쩡해야 한다.
    @Test
    fun `재사용 감지는 그 세션만 무효화하고 다른 세션은 건드리지 않는다`() {
        val userId = UUID.randomUUID()
        val phone = sid()
        val laptop = sid()
        store.save(userId, phone, "A")
        store.save(userId, laptop, "laptop-token")
        store.rotateOrReplay(userId, phone, presented = "A", candidateRefreshToken = "B")
        redisTemplate.delete("refresh:grace:$userId:$phone") // grace TTL 경과 시뮬레이션

        val outcome = store.rotateOrReplay(userId, phone, presented = "A", candidateRefreshToken = "C")

        assertIs<RefreshOutcome.ReuseDetected>(outcome)
        assertNull(store.get(userId, phone))
        assertEquals("laptop-token", store.get(userId, laptop))

        store.deleteAll(userId)
    }

    @Test
    fun `deleteAll 은 그 유저의 전 세션을 지운다`() {
        val userId = UUID.randomUUID()
        val phone = sid()
        val laptop = sid()
        store.save(userId, phone, "phone-token")
        store.save(userId, laptop, "laptop-token")

        store.deleteAll(userId)

        assertNull(store.get(userId, phone))
        assertNull(store.get(userId, laptop))
        assertEquals(false, redisTemplate.hasKey("refresh:idx:$userId"))
    }

    // 인덱스는 전 세션 무효화가 키를 찾아가는 유일한 경로다. 회전이 current TTL 만 늘리고 인덱스를 방치하면
    // 계속 쓰는 세션에서 인덱스가 먼저 만료되고, 그 뒤 탈퇴·동의철회가 살아 있는 세션을 못 찾는다.
    // 인덱스가 이미 사라진 뒤에는 인덱스 기반 설계상 복구가 불가능하므로, 애초에 만료되지 않게 미는 것을 검증한다.
    @Test
    fun `회전은 세션 인덱스의 TTL 도 함께 민다`() {
        val userId = UUID.randomUUID()
        val sessionId = sid()
        store.save(userId, sessionId, "A")
        // 인덱스가 곧 만료될 상황을 만든다 (오래 살아 있는 세션에서 자연히 도달하는 상태).
        redisTemplate.expire("refresh:idx:$userId", 2, TimeUnit.SECONDS)

        store.rotateOrReplay(userId, sessionId, presented = "A", candidateRefreshToken = "B")

        val ttl = redisTemplate.getExpire("refresh:idx:$userId", TimeUnit.SECONDS)
        assertTrue(ttl > 60, "회전이 인덱스 TTL 을 밀지 않았다 (남은 TTL=${ttl}s)")
        // 그래서 이후 전 세션 무효화가 회전된 세션까지 찾아간다.
        store.deleteAll(userId)
        assertNull(store.get(userId, sessionId))
    }

    @Test
    fun `deleteAll 중 새로 로그인한 세션도 인덱스에 남아 다음 무효화에 잡힌다`() {
        val userId = UUID.randomUUID()
        val old = sid()
        val fresh = sid()
        store.save(userId, old, "old-token")

        store.deleteAll(userId)
        // 무효화 직후 새 로그인 — 인덱스가 다시 서야 다음 전 세션 무효화가 이 세션을 찾는다.
        store.save(userId, fresh, "fresh-token")

        store.deleteAll(userId)

        assertNull(store.get(userId, fresh))
    }

    @Test
    fun `deleteAll 은 다른 유저의 세션을 건드리지 않는다`() {
        val mine = UUID.randomUUID()
        val other = UUID.randomUUID()
        val mySession = sid()
        val otherSession = sid()
        store.save(mine, mySession, "mine")
        store.save(other, otherSession, "other")

        store.deleteAll(mine)

        assertNull(store.get(mine, mySession))
        assertEquals("other", store.get(other, otherSession))

        store.deleteAll(other)
    }

    // ── 회전·grace·재사용 판정 ─────────────────────────────────────────

    @Test
    fun `rotateOrReplay - 현재 토큰과 일치하면 Rotated 이고 새 토큰으로 회전된다`() {
        val userId = UUID.randomUUID()
        val sessionId = sid()
        store.save(userId, sessionId, "A")

        val outcome = store.rotateOrReplay(userId, sessionId, presented = "A", candidateRefreshToken = "B")

        assertIs<RefreshOutcome.Rotated>(outcome)
        assertEquals("B", store.get(userId, sessionId))

        store.delete(userId, sessionId)
    }

    @Test
    fun `rotateOrReplay - grace 창 안에 옛 토큰으로 다시 오면 Replayed 로 같은 새 토큰을 반환한다`() {
        val userId = UUID.randomUUID()
        val sessionId = sid()
        store.save(userId, sessionId, "A")
        store.rotateOrReplay(userId, sessionId, presented = "A", candidateRefreshToken = "B")

        // 옛 토큰 A 로 동시 재요청 — candidate C 는 버려지고 승자 토큰 B 가 멱등 반환돼야 한다
        val outcome = store.rotateOrReplay(userId, sessionId, presented = "A", candidateRefreshToken = "C")

        assertIs<RefreshOutcome.Replayed>(outcome)
        assertEquals("B", outcome.refreshToken)
        assertEquals("B", store.get(userId, sessionId)) // current 는 그대로 B (재회전 없음)

        store.delete(userId, sessionId)
    }

    @Test
    fun `rotateOrReplay - 저장된 토큰이 없으면 Expired 이다`() {
        val outcome =
            store.rotateOrReplay(UUID.randomUUID(), sid(), presented = "A", candidateRefreshToken = "B")

        assertIs<RefreshOutcome.Expired>(outcome)
    }

    @Test
    fun `rotateOrReplay - grace 밖에서 옛 토큰을 재사용하면 ReuseDetected 이고 current 가 무효화된다`() {
        val userId = UUID.randomUUID()
        val sessionId = sid()
        store.save(userId, sessionId, "A")
        store.rotateOrReplay(userId, sessionId, presented = "A", candidateRefreshToken = "B")

        // grace TTL 경과 시뮬레이션 — grace 키만 제거 (실시간 10초 대기 회피)
        redisTemplate.delete("refresh:grace:$userId:$sessionId")

        val outcome = store.rotateOrReplay(userId, sessionId, presented = "A", candidateRefreshToken = "C")

        assertIs<RefreshOutcome.ReuseDetected>(outcome)
        assertNull(store.get(userId, sessionId)) // 살아있던 current(B) 도 삭제

        store.delete(userId, sessionId)
    }

    @Test
    fun `rotateOrReplay - grace 창 안이어도 current 도 grace 의 옛 토큰도 아닌 토큰은 ReuseDetected 이다`() {
        val userId = UUID.randomUUID()
        val sessionId = sid()
        store.save(userId, sessionId, "A")
        store.rotateOrReplay(userId, sessionId, presented = "A", candidateRefreshToken = "B")

        // grace 가 살아있어도 A·B 둘 다 아닌 무관 토큰은 replay 가 아니라 재사용 의심으로 처리돼야 한다
        val outcome = store.rotateOrReplay(userId, sessionId, presented = "X", candidateRefreshToken = "C")

        assertIs<RefreshOutcome.ReuseDetected>(outcome)
        assertNull(store.get(userId, sessionId))

        store.delete(userId, sessionId)
    }

    @Test
    fun `재사용으로 무효화된 세션은 인덱스에서도 빠진다`() {
        val userId = UUID.randomUUID()
        val sessionId = sid()
        store.save(userId, sessionId, "A")
        store.rotateOrReplay(userId, sessionId, presented = "A", candidateRefreshToken = "B")
        redisTemplate.delete("refresh:grace:$userId:$sessionId")

        store.rotateOrReplay(userId, sessionId, presented = "A", candidateRefreshToken = "C")

        assertEquals(false, redisTemplate.opsForSet().isMember("refresh:idx:$userId", sessionId))

        store.deleteAll(userId)
    }

    @Test
    fun `delete 는 grace 키도 함께 지운다`() {
        val userId = UUID.randomUUID()
        val sessionId = sid()
        store.save(userId, sessionId, "A")
        store.rotateOrReplay(userId, sessionId, presented = "A", candidateRefreshToken = "B")

        store.delete(userId, sessionId)

        assertNull(redisTemplate.opsForValue().get("refresh:grace:$userId:$sessionId"))
    }

    @Test
    fun `rotateOrReplay - 같은 옛 토큰 동시 요청은 한 번만 회전하고 모두 같은 새 토큰으로 수렴한다`() {
        val userId = UUID.randomUUID()
        val sessionId = sid()
        store.save(userId, sessionId, "A")

        val threads = 8
        val executor = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val start = CountDownLatch(1)
        try {
            val futures =
                (0 until threads).map { i ->
                    executor.submit<RefreshOutcome> {
                        ready.countDown()
                        start.await()
                        // 각 스레드가 서로 다른 candidate 를 제시 — 승자의 것만 채택돼야 한다
                        store.rotateOrReplay(userId, sessionId, presented = "A", candidateRefreshToken = "cand-$i")
                    }
                }
            ready.await()
            start.countDown()
            val outcomes = futures.map { it.get(10, TimeUnit.SECONDS) }
            executor.shutdown()

            // 정확히 하나만 Rotated, 나머지는 Replayed
            assertEquals(1, outcomes.count { it is RefreshOutcome.Rotated })
            assertEquals(threads - 1, outcomes.count { it is RefreshOutcome.Replayed })

            // 모든 요청이 인지한 "새 토큰"이 저장된 current 와 동일하게 수렴
            val winner = store.get(userId, sessionId)
            assertNotNull(winner)
            val seenTokens =
                outcomes
                    .map { if (it is RefreshOutcome.Replayed) it.refreshToken else winner }
                    .toSet()
            assertEquals(setOf(winner), seenTokens)
        } finally {
            executor.shutdownNow()
            store.delete(userId, sessionId)
        }
    }
}
