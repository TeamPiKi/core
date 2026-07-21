package com.depromeet.piki.support

import com.depromeet.piki.auth.infrastructure.redis.WithdrawnTokenStore
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// 통합 테스트용 in-memory WithdrawnTokenStore (실제 Redis 격리). RedisRefreshTokenStore 처럼
// 실제 Redis 구현은 RedisWithdrawnTokenStoreIntegrationTest 에서 별도로 검증한다.
class StubWithdrawnTokenStore : WithdrawnTokenStore {
    private val withdrawn = ConcurrentHashMap.newKeySet<UUID>()

    // markWithdrawn 호출 관측(재시도 검증에서 "몇 번 호출됐나"를 본다).
    val markWithdrawnInvocations: MutableList<UUID> = Collections.synchronizedList(mutableListOf())

    // 기본 동작: 실제 저장. 실패·재시도 시나리오 테스트가 behavior 를 교체하며,
    // 2회째 성공 경로가 필요하면 이 defaultBehavior 를 직접 호출한다(withdrawn 클로저에 접근).
    val defaultBehavior: (UUID) -> Unit = { userId -> withdrawn.add(userId) }
    var behavior: (UUID) -> Unit = defaultBehavior

    override fun markWithdrawn(userId: UUID) {
        markWithdrawnInvocations.add(userId)
        behavior(userId)
    }

    override fun isWithdrawn(userId: UUID): Boolean = userId in withdrawn

    fun reset() {
        withdrawn.clear()
        markWithdrawnInvocations.clear()
        behavior = defaultBehavior
    }
}
