package com.depromeet.piki.support

import com.depromeet.piki.auth.infrastructure.redis.RefreshOutcome
import com.depromeet.piki.auth.infrastructure.redis.RefreshTokenStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class StubRefreshTokenStore : RefreshTokenStore {
    // 키가 (userId, sessionId) 다 — 실제 Redis 키 구조를 그대로 모델링해야 "다른 기기가 서로 안 덮어쓴다" 를
    // stub 위에서도 검증할 수 있다(#893).
    private val store = ConcurrentHashMap<Pair<UUID, String>, String>()

    // 회전 직후 옛→새 토큰 매핑 (RedisRefreshTokenStore 의 grace 키 대응). TTL 은 모델링하지 않는다 —
    // grace 만료(→ ReuseDetected) 검증은 실제 Lua 를 쓰는 RedisRefreshTokenStoreIntegrationTest 가 책임진다.
    private val grace = ConcurrentHashMap<Pair<UUID, String>, Pair<String, String>>()

    // default: 정상 저장. 테스트에서 onSave = { _, _, _ -> throw ... } 로 Redis 장애 시뮬레이션.
    var onSave: (UUID, String, String) -> Unit = { userId, sessionId, token ->
        store[userId to sessionId] = token
    }

    override fun save(
        userId: UUID,
        sessionId: String,
        refreshToken: String,
    ) = onSave(userId, sessionId, refreshToken)

    override fun get(
        userId: UUID,
        sessionId: String,
    ): String? = store[userId to sessionId]

    // default: 정상 삭제. 테스트에서 onDelete = { _, _ -> throw ... } 로 Redis 장애 시뮬레이션.
    var onDelete: (UUID, String) -> Unit = { userId, sessionId ->
        store.remove(userId to sessionId)
        grace.remove(userId to sessionId)
    }

    override fun delete(
        userId: UUID,
        sessionId: String,
    ) = onDelete(userId, sessionId)

    // default: 그 유저의 전 세션 제거. 테스트에서 onDeleteAll = { throw ... } 로 Redis 장애 시뮬레이션.
    var onDeleteAll: (UUID) -> Unit = { userId ->
        store.keys.filter { it.first == userId }.forEach { store.remove(it) }
        grace.keys.filter { it.first == userId }.forEach { grace.remove(it) }
    }

    override fun deleteAll(userId: UUID) = onDeleteAll(userId)

    // 실제 Lua 와 동일 판정. Redis 싱글스레드 직렬화를 @Synchronized 로 모델링해, 동시 요청이 한쪽만 회전하고
    // 나머지는 grace replay 로 같은 토큰에 수렴하는 것을 stub 에서도 결정적으로 재현한다.
    @Synchronized
    override fun rotateOrReplay(
        userId: UUID,
        sessionId: String,
        presented: String,
        candidateRefreshToken: String,
    ): RefreshOutcome {
        val key = userId to sessionId
        val cur = store[key]
        if (cur == presented) {
            store[key] = candidateRefreshToken
            grace[key] = presented to candidateRefreshToken
            return RefreshOutcome.Rotated
        }
        grace[key]?.let { (old, new) ->
            if (old == presented) return RefreshOutcome.Replayed(new)
        }
        cur ?: return RefreshOutcome.Expired
        // 무효화 범위는 이 세션 하나 — 다른 기기의 슬롯은 건드리지 않는다(#893 의 핵심).
        store.remove(key)
        grace.remove(key)
        return RefreshOutcome.ReuseDetected
    }

    // 테스트에서 grace TTL 경과를 시뮬레이션 — grace 매핑만 제거해, 이후 옛 토큰 재사용이 replay 가 아니라
    // ReuseDetected(세션 무효화) 로 가게 한다. 실제 RedisRefreshTokenStore 에선 grace 키 TTL 만료가 한다.
    // 호출부(HTTP 레벨 테스트)는 응답으로 받은 토큰만 알고 sid 는 모르므로 그 유저의 grace 를 통째로 비운다.
    fun expireGrace(userId: UUID) {
        grace.keys.filter { it.first == userId }.forEach { grace.remove(it) }
    }

    // 세션 id 를 모르는 호출부(HTTP 레벨 테스트)가 저장된 토큰을 확인할 때 쓴다.
    fun anyToken(userId: UUID): String? = store.entries.firstOrNull { it.key.first == userId }?.value

    fun reset() {
        store.clear()
        grace.clear()
        onSave = { userId, sessionId, token -> store[userId to sessionId] = token }
        onDelete = { userId, sessionId ->
            store.remove(userId to sessionId)
            grace.remove(userId to sessionId)
        }
        onDeleteAll = { userId ->
            store.keys.filter { it.first == userId }.forEach { store.remove(it) }
            grace.keys.filter { it.first == userId }.forEach { grace.remove(it) }
        }
    }
}
