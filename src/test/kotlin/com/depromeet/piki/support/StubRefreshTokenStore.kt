package com.depromeet.piki.support

import com.depromeet.piki.auth.infrastructure.redis.RefreshOutcome
import com.depromeet.piki.auth.infrastructure.redis.RefreshTokenStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class StubRefreshTokenStore : RefreshTokenStore {
    private val store = ConcurrentHashMap<UUID, String>()

    // 회전 직후 옛→새 토큰 매핑 (RedisRefreshTokenStore 의 grace 키 대응). TTL 은 모델링하지 않는다 —
    // grace 만료(→ ReuseDetected) 검증은 실제 Lua 를 쓰는 RedisRefreshTokenStoreIntegrationTest 가 책임진다.
    private val grace = ConcurrentHashMap<UUID, Pair<String, String>>()

    // default: 정상 저장. 테스트에서 onSave = { _, _ -> throw ... } 로 Redis 장애 시뮬레이션.
    var onSave: (UUID, String) -> Unit = { userId, token -> store[userId] = token }

    override fun save(
        userId: UUID,
        refreshToken: String,
    ) = onSave(userId, refreshToken)

    override fun get(userId: UUID): String? = store[userId]

    // default: 정상 삭제. 테스트에서 onDelete = { throw ... } 로 Redis 장애 시뮬레이션.
    var onDelete: (UUID) -> Unit = { userId ->
        store.remove(userId)
        grace.remove(userId)
    }

    override fun delete(userId: UUID) = onDelete(userId)

    // 실제 Lua 와 동일 판정. Redis 싱글스레드 직렬화를 @Synchronized 로 모델링해, 동시 요청이 한쪽만 회전하고
    // 나머지는 grace replay 로 같은 토큰에 수렴하는 것을 stub 에서도 결정적으로 재현한다.
    @Synchronized
    override fun rotateOrReplay(
        userId: UUID,
        presented: String,
        candidateRefreshToken: String,
    ): RefreshOutcome {
        val cur = store[userId]
        if (cur == presented) {
            store[userId] = candidateRefreshToken
            grace[userId] = presented to candidateRefreshToken
            return RefreshOutcome.Rotated
        }
        grace[userId]?.let { (old, new) ->
            if (old == presented) return RefreshOutcome.Replayed(new)
        }
        cur ?: return RefreshOutcome.Expired
        store.remove(userId)
        grace.remove(userId)
        return RefreshOutcome.ReuseDetected
    }

    // 테스트에서 grace TTL 경과를 시뮬레이션 — grace 매핑만 제거해, 이후 옛 토큰 재사용이 replay 가 아니라
    // ReuseDetected(family invalidation) 로 가게 한다. 실제 RedisRefreshTokenStore 에선 grace 키 TTL 만료가 한다.
    fun expireGrace(userId: UUID) {
        grace.remove(userId)
    }

    fun reset() {
        store.clear()
        grace.clear()
        onSave = { userId, token -> store[userId] = token }
        onDelete = { userId ->
            store.remove(userId)
            grace.remove(userId)
        }
    }
}
